package com.vitalis.core

/**
 * A system/environment error distinct from a liveness [LivenessResult.Failure]:
 * these mean the session could not run correctly, not that the subject failed the check.
 * Delivered via [LivenessListener.onError].
 */
data class LivenessError(
    val code: Code,
    val message: String,
    val cause: Throwable? = null
) {
    enum class Code {
        /** Device exposes no front-facing camera (§8). */
        NO_FRONT_CAMERA,
        /** Camera permission not granted or revoked mid-session (§8). */
        CAMERA_PERMISSION_DENIED,
        /** CameraX / device camera failure. */
        CAMERA_ERROR,
        /** Passive model requested but not available on this build (see feature doc §5/§10.5). */
        PASSIVE_MODEL_UNAVAILABLE,
        /** Any other unexpected failure. */
        INTERNAL
    }
}
