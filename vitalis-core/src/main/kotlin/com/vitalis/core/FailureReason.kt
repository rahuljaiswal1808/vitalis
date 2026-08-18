package com.vitalis.core

/**
 * Specific, non-generic failure reasons surfaced to the caller (F5) so the host app
 * can guide the user ("move closer", "improve lighting", "blink when prompted").
 */
enum class FailureReason {
    NO_FACE,
    MULTIPLE_FACES,
    FACE_TOO_SMALL,
    FACE_NOT_FRONTAL,
    LOW_LIGHT,
    CHALLENGE_TIMEOUT,
    CHALLENGE_FAILED,
    SPOOF_SUSPECTED,
    CAMERA_ERROR
}
