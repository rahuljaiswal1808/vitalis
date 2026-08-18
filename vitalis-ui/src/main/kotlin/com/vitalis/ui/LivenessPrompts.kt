package com.vitalis.ui

import com.vitalis.core.ChallengeType
import com.vitalis.core.FailureReason
import com.vitalis.core.LivenessState

/**
 * Maps engine states/failures to short, user-facing guidance strings (F5).
 * Kept as plain functions (not string resources) so the mapping is testable and the
 * host app can localize by overriding.
 */
object LivenessPrompts {

    fun forState(state: LivenessState): String = when (state) {
        LivenessState.SearchingFace -> "Position your face in the oval"
        LivenessState.FaceFound -> "Hold still…"
        is LivenessState.AwaitingChallenge -> forChallenge(state.type)
        LivenessState.Verifying -> "Verifying…"
    }

    fun forChallenge(type: ChallengeType): String = when (type) {
        ChallengeType.BLINK -> "Blink slowly"
        ChallengeType.HEAD_TURN_LEFT -> "Turn your head to the left"
        ChallengeType.HEAD_TURN_RIGHT -> "Turn your head to the right"
        ChallengeType.SMILE -> "Smile"
    }

    fun forFailure(reason: FailureReason): String = when (reason) {
        FailureReason.NO_FACE -> "No face detected — face the camera"
        FailureReason.MULTIPLE_FACES -> "Only one person should be in frame"
        FailureReason.FACE_TOO_SMALL -> "Move a little closer"
        FailureReason.FACE_NOT_FRONTAL -> "Look straight at the camera"
        FailureReason.LOW_LIGHT -> "Find better lighting"
        FailureReason.CHALLENGE_TIMEOUT -> "Timed out — let's try again"
        FailureReason.CHALLENGE_FAILED -> "We couldn't complete the check — try again"
        FailureReason.SPOOF_SUSPECTED -> "Verification failed"
        FailureReason.CAMERA_ERROR -> "Camera error"
    }
}
