package com.vitalis.camera

import android.content.Context
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vitalis.core.LivenessError
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX wrapper that is **hard-locked to the front lens** (F1).
 *
 * There is deliberately no lens-switch API and no back-camera fallback: the only
 * selector this class will ever bind is [CameraSelector.DEFAULT_FRONT_CAMERA]. If the
 * device has no front camera it fails fast with [LivenessError.Code.NO_FRONT_CAMERA]
 * rather than silently using the back camera (§8).
 *
 * Frames are delivered on a dedicated single-thread analysis executor (keep-latest
 * back-pressure) so the downstream face + engine pipeline is serialized.
 */
class FrontCameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    fun interface FrameAnalyzer {
        /**
         * Called for each frame. The implementation MUST call [ImageProxy.close] when done,
         * or the pipeline stalls. Runs on the analysis executor, not the main thread.
         */
        fun analyze(image: ImageProxy)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var started = false

    /** @return true if this device exposes any front-facing camera. */
    fun hasFrontCamera(provider: ProcessCameraProvider): Boolean =
        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)

    /**
     * Bind preview (optional) + image analysis to the front camera.
     *
     * @param previewView optional target for the live preview; pass null for headless analysis.
     * @param analyzer per-frame callback.
     * @param onError invoked on the main thread if binding fails.
     */
    @MainThread
    fun start(
        previewView: PreviewView?,
        analyzer: FrameAnalyzer,
        onError: (LivenessError) -> Unit
    ) {
        if (started) return
        started = true
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                if (!hasFrontCamera(provider)) {
                    onError(
                        LivenessError(
                            LivenessError.Code.NO_FRONT_CAMERA,
                            "This device has no front-facing camera."
                        )
                    )
                    stop()
                    return@addListener
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { image -> analyzer.analyze(image) }

                val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
                if (previewView != null) {
                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }
                    useCases.add(preview)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,   // F1: front only, no exceptions
                    *useCases.toTypedArray()
                )
            } catch (t: Throwable) {
                onError(
                    LivenessError(
                        LivenessError.Code.CAMERA_ERROR,
                        "Failed to start front camera: ${t.message}",
                        t
                    )
                )
                stop()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbind all use cases and shut down the analysis executor. Idempotent. */
    @MainThread
    fun stop() {
        started = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        cameraProvider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}
