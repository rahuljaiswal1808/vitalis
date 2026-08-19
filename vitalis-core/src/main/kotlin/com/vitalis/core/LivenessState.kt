package com.vitalis.core

/** Observable phases of a liveness session (§7). Delivered via [LivenessListener.onStateChanged]. */
sealed class LivenessState {
    /** Camera is live but no valid single frontal face has been found yet. */
    object SearchingFace : LivenessState()

    /** A single, well-framed, frontal face is present. */
    object FaceFound : LivenessState()

    /** A challenge has been issued; the host app should prompt the user to perform [type]. */
    data class AwaitingChallenge(val type: ChallengeType) : LivenessState()

    /** All challenges passed; running final checks (passive model, etc.). */
    object Verifying : LivenessState()
}
