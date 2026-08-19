package com.vitalis.face

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.vitalis.core.FaceSignals
import kotlin.math.min

/**
 * Wraps ML Kit Face Detection and turns each frame into a neutral [FaceSignals]
 * for the pure-Kotlin engine (§5, §10.3).
 *
 * Classification is enabled so we get `smilingProbability` and per-eye
 * `*EyeOpenProbability` — those directly power the blink and smile challenges
 * without any custom model.
 */
class FaceAnalyzer(
    minFaceRatio: Float = 0.15f
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            // Track by proportional min size so tiny/far faces are ignored early.
            .setMinFaceSize(minFaceRatio)
            .enableTracking()
            .build()
    )

    fun interface Callback {
        /**
         * Delivered on the ML Kit worker. The [image] is still open; the caller MUST
         * close it (after optionally encoding it for capture).
         */
        fun onSignals(signals: FaceSignals, image: ImageProxy)
    }

    /**
     * @param brightness pre-computed average luma in [0,1] (or -1), supplied by the caller
     *   which already holds the frame's Y plane.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun analyze(
        image: ImageProxy,
        brightness: Float,
        nowMs: Long,
        callback: Callback,
        onError: (Throwable) -> Unit
    ) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        // Effective frame dimensions in the rotated coordinate space ML Kit reports in.
        val frameW: Int
        val frameH: Int
        if (rotation == 90 || rotation == 270) {
            frameW = image.height; frameH = image.width
        } else {
            frameW = image.width; frameH = image.height
        }
        val smallerDim = min(frameW, frameH).coerceAtLeast(1)
        // Single clock across frames + timeout ticker is supplied by the orchestrator.
        val ts = nowMs

        detector.process(input)
            .addOnSuccessListener { faces ->
                val signals = if (faces.isEmpty()) {
                    FaceSignals.empty(ts).copy(brightness = brightness)
                } else {
                    // Largest face by box area drives gating; count still reflects all faces.
                    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val box = face.boundingBox
                    val ratio = maxOf(box.width(), box.height()).toFloat() / smallerDim
                    FaceSignals(
                        faceCount = faces.size,
                        boundingBoxRatio = ratio,
                        yawDegrees = face.headEulerAngleY,
                        pitchDegrees = face.headEulerAngleX,
                        rollDegrees = face.headEulerAngleZ,
                        leftEyeOpenProbability = face.leftEyeOpenProbability ?: FaceSignals.UNKNOWN,
                        rightEyeOpenProbability = face.rightEyeOpenProbability ?: FaceSignals.UNKNOWN,
                        smilingProbability = face.smilingProbability ?: FaceSignals.UNKNOWN,
                        brightness = brightness,
                        timestampMs = ts
                    )
                }
                callback.onSignals(signals, image)
            }
            .addOnFailureListener { e ->
                onError(e)
                image.close()
            }
    }

    fun close() {
        detector.close()
    }
}
