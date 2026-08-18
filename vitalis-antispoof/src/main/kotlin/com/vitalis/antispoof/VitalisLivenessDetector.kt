package com.vitalis.antispoof

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.vitalis.camera.FrameUtils
import com.vitalis.camera.FrontCameraSource
import com.vitalis.core.EngineEvent
import com.vitalis.core.FailureReason
import com.vitalis.core.LivenessConfig
import com.vitalis.core.LivenessDetector
import com.vitalis.core.LivenessEngine
import com.vitalis.core.LivenessError
import com.vitalis.core.LivenessListener
import com.vitalis.core.LivenessResult
import com.vitalis.core.LivenessState
import com.vitalis.face.FaceAnalyzer

/**
 * Concrete [LivenessDetector] that stitches the modules together (§5, §10):
 * front camera (F1) → ML Kit face signals → pure engine (F2–F4 active) → optional
 * passive spoof model (F4 passive).
 *
 * Threading: ML Kit success callbacks and the timeout ticker both run on the main thread,
 * so the engine is only ever touched from one thread — no locks needed. Frame luma and
 * JPEG/model work run on the camera analysis executor / inside the callback.
 *
 * Obtain instances via [com.vitalis.antispoof.Vitalis]. Not reusable across sessions;
 * create one, [start], and [stop].
 */
class VitalisLivenessDetector internal constructor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val passiveScorer: PassiveSpoofScorer
) : LivenessDetector {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: LivenessEngine? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var camera: FrontCameraSource? = null
    private var listener: LivenessListener? = null
    private var config: LivenessConfig = LivenessConfig()
    private var finished = false

    private val ticker = object : Runnable {
        override fun run() {
            val e = engine ?: return
            dispatch(e.onTick(SystemClock.elapsedRealtime()))
            if (!finished) mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    @MainThread
    override fun start(config: LivenessConfig, listener: LivenessListener) {
        config.validate()
        this.config = config
        this.listener = listener
        this.finished = false

        val engine = LivenessEngine(config).also { this.engine = it }
        this.faceAnalyzer = FaceAnalyzer(minFaceRatio = config.minFaceRatio)
        val camera = FrontCameraSource(context, lifecycleOwner).also { this.camera = it }

        dispatch(engine.start(SystemClock.elapsedRealtime()))
        mainHandler.postDelayed(ticker, TICK_INTERVAL_MS)

        camera.start(
            previewView = previewView,
            analyzer = { image -> onFrame(image) },
            onError = { error -> finishWithError(error) }
        )
    }

    /** Runs on the camera analysis executor. */
    @androidx.camera.core.ExperimentalGetImage
    private fun onFrame(image: ImageProxy) {
        val analyzer = faceAnalyzer
        if (analyzer == null || finished) {
            image.close()
            return
        }
        val brightness = try {
            FrameUtils.averageLuma(image)
        } catch (_: Throwable) {
            -1f
        }
        val now = SystemClock.elapsedRealtime()
        analyzer.analyze(
            image = image,
            brightness = brightness,
            nowMs = now,
            callback = { signals, img ->
                // Back on the main thread (ML Kit default executor).
                val e = engine
                if (e == null || finished) {
                    img.close()
                } else {
                    handleEvents(e.onFrame(signals), img)
                }
            },
            onError = { t ->
                finishWithError(
                    LivenessError(LivenessError.Code.INTERNAL, "Face analysis failed: ${t.message}", t)
                )
            }
        )
    }

    /** Runs on the main thread. Closes [image] when done. */
    private fun handleEvents(events: List<EngineEvent>, image: ImageProxy) {
        var closed = false
        try {
            for (event in events) {
                when (event) {
                    is EngineEvent.StateChanged -> listener?.onStateChanged(event.state)
                    is EngineEvent.Rejected -> {
                        finishWithResult(LivenessResult.Failure(event.reason))
                        return
                    }
                    is EngineEvent.Verified -> {
                        // Capture the verified frame while it is still open.
                        val jpeg = if (config.returnCapturedFrame) {
                            runCatching { FrameUtils.toJpeg(image) }.getOrDefault(ByteArray(0))
                        } else {
                            ByteArray(0)
                        }
                        val result = verify(event.confidence, image)
                        closed = true
                        image.close()
                        finishWithResult(
                            when (result) {
                                is Verdict.Spoof -> LivenessResult.Failure(FailureReason.SPOOF_SUSPECTED)
                                is Verdict.Real -> LivenessResult.Success(jpeg, result.confidence)
                            }
                        )
                        return
                    }
                }
            }
        } finally {
            if (!closed) image.close()
        }
    }

    private sealed interface Verdict {
        data class Real(val confidence: Float) : Verdict
        object Spoof : Verdict
    }

    /** Apply the passive tier if available; otherwise accept the active-tier result. */
    private fun verify(activeConfidence: Float, image: ImageProxy): Verdict {
        if (!config.enablePassiveModel || !passiveScorer.isAvailable) {
            return Verdict.Real(activeConfidence)
        }
        val realProb = passiveScorer.scoreRealProbability(image)
        if (realProb < config.passiveRealProbThreshold) return Verdict.Spoof
        // Combine: conservative product of active and passive confidence.
        val combined = (activeConfidence * realProb).coerceIn(0f, 1f)
        return Verdict.Real(combined)
    }

    private fun dispatch(events: List<EngineEvent>) {
        for (event in events) {
            when (event) {
                is EngineEvent.StateChanged -> listener?.onStateChanged(event.state)
                is EngineEvent.Rejected -> finishWithResult(LivenessResult.Failure(event.reason))
                is EngineEvent.Verified ->
                    finishWithResult(LivenessResult.Success(ByteArray(0), event.confidence))
            }
        }
    }

    private fun finishWithResult(result: LivenessResult) {
        if (finished) return
        finished = true
        val l = listener
        teardown()
        mainHandler.post { l?.onResult(result) }
    }

    private fun finishWithError(error: LivenessError) {
        if (finished) return
        finished = true
        val l = listener
        teardown()
        mainHandler.post { l?.onError(error) }
    }

    @MainThread
    override fun stop() {
        finished = true
        teardown()
    }

    private fun teardown() {
        mainHandler.removeCallbacks(ticker)
        camera?.stop()
        camera = null
        faceAnalyzer?.close()
        faceAnalyzer = null
        passiveScorer.close()
        engine = null
    }

    private companion object {
        const val TICK_INTERVAL_MS = 500L
    }
}
