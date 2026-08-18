package com.vitalis.core

/**
 * Neutral, per-frame face measurements consumed by [LivenessEngine].
 *
 * This is the seam that keeps the core engine free of ML Kit / Android types:
 * the `vitalis-face` module converts a detected face into a [FaceSignals], and the
 * pure-Kotlin engine reasons only about these numbers. That makes the whole state
 * machine and challenge logic unit-testable with no camera and no device (§9).
 *
 * Probabilities are in [0,1]; use [UNKNOWN] (-1) when a value is unavailable.
 */
data class FaceSignals(
    /** Number of faces detected in the frame. */
    val faceCount: Int,
    /** Largest face's bounding-box size as a fraction of the smaller frame dimension. */
    val boundingBoxRatio: Float,
    /** Head Euler angle Y (yaw): negative = turned to subject's left, positive = right. */
    val yawDegrees: Float,
    /** Head Euler angle X (pitch): up/down nod. */
    val pitchDegrees: Float,
    /** Head Euler angle Z (roll): in-plane tilt. */
    val rollDegrees: Float,
    /** Probability the left eye is open, or [UNKNOWN]. */
    val leftEyeOpenProbability: Float,
    /** Probability the right eye is open, or [UNKNOWN]. */
    val rightEyeOpenProbability: Float,
    /** Probability the subject is smiling, or [UNKNOWN]. */
    val smilingProbability: Float,
    /** Average frame luma in [0,1], or [UNKNOWN]. */
    val brightness: Float,
    /** Source-clock timestamp of the frame, milliseconds. */
    val timestampMs: Long
) {
    companion object {
        const val UNKNOWN: Float = -1f

        /** Convenience for "no face this frame" at [timestampMs]. */
        @JvmStatic
        fun empty(timestampMs: Long): FaceSignals = FaceSignals(
            faceCount = 0,
            boundingBoxRatio = 0f,
            yawDegrees = 0f,
            pitchDegrees = 0f,
            rollDegrees = 0f,
            leftEyeOpenProbability = UNKNOWN,
            rightEyeOpenProbability = UNKNOWN,
            smilingProbability = UNKNOWN,
            brightness = UNKNOWN,
            timestampMs = timestampMs
        )
    }
}
