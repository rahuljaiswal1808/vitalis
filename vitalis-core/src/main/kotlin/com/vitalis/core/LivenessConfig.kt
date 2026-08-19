package com.vitalis.core

/**
 * Tunable parameters for a liveness session.
 *
 * All thresholds have conservative defaults derived from the feature doc (§6).
 * Call [validate] (invoked automatically by the engine) to fail fast on nonsense.
 *
 * Java-friendly: this is a plain data class with default values; Java callers can
 * use [LivenessConfig.builder] for a fluent, default-preserving construction.
 */
data class LivenessConfig(
    /** Minimum face bounding-box size as a fraction of the smaller frame dimension (F3). */
    val minFaceRatio: Float = 0.25f,
    /** Max absolute yaw (head turn left/right) for a face to count as "frontal", degrees (F3). */
    val maxYawDegrees: Float = 15f,
    /** Max absolute pitch (head nod up/down) for a face to count as "frontal", degrees (F3). */
    val maxPitchDegrees: Float = 15f,
    /** Which challenges may be issued. One is chosen at random per required challenge. */
    val challengeTypes: Set<ChallengeType> = setOf(ChallengeType.BLINK),
    /** How many distinct challenges must be passed before verifying. */
    val requiredChallengeCount: Int = 1,
    /** Per-challenge time budget before [FailureReason.CHALLENGE_TIMEOUT]. */
    val challengeTimeoutMs: Long = 5_000L,
    /** Total session budget across all phases. */
    val sessionTimeoutMs: Long = 30_000L,
    /** Face-loss events during a challenge tolerated before failing (§7). */
    val maxFaceLossResets: Int = 3,
    /** Whether the passive TFLite spoof tier should run (orchestrator-side, F4). */
    val enablePassiveModel: Boolean = true,
    /** Minimum average frame brightness [0,1] to attempt liveness; <=0 disables the gate (§8). */
    val minBrightness: Float = 0.15f,
    /** Eye-open probability below which an eye is considered closed (blink detection). */
    val eyeClosedThreshold: Float = 0.35f,
    /** Eye-open probability above which an eye is considered open (blink detection). */
    val eyeOpenThreshold: Float = 0.65f,
    /** Yaw magnitude (degrees) that must be reached to satisfy a HEAD_TURN challenge. */
    val headTurnYawDegrees: Float = 22f,
    /** Smile probability above which the SMILE challenge is satisfied. */
    val smileThreshold: Float = 0.7f,
    /** Smile probability the face must first be below to arm the SMILE transition. */
    val neutralSmileThreshold: Float = 0.3f,
    /** Whether [LivenessResult.Success] should carry the verified frame as JPEG bytes (F6, §11). */
    val returnCapturedFrame: Boolean = true,
    /** Passive tier: minimum "real" probability [0,1] to accept; below this -> SPOOF_SUSPECTED. */
    val passiveRealProbThreshold: Float = 0.5f
) {
    /** @throws IllegalArgumentException with a specific message on invalid configuration. */
    fun validate() {
        require(minFaceRatio in 0f..1f) { "minFaceRatio must be in [0,1], was $minFaceRatio" }
        require(maxYawDegrees in 0f..90f) { "maxYawDegrees must be in [0,90], was $maxYawDegrees" }
        require(maxPitchDegrees in 0f..90f) { "maxPitchDegrees must be in [0,90], was $maxPitchDegrees" }
        require(challengeTypes.isNotEmpty()) { "challengeTypes must not be empty" }
        require(requiredChallengeCount >= 1) { "requiredChallengeCount must be >= 1, was $requiredChallengeCount" }
        require(challengeTimeoutMs > 0) { "challengeTimeoutMs must be > 0, was $challengeTimeoutMs" }
        require(sessionTimeoutMs >= challengeTimeoutMs) {
            "sessionTimeoutMs ($sessionTimeoutMs) must be >= challengeTimeoutMs ($challengeTimeoutMs)"
        }
        require(maxFaceLossResets >= 0) { "maxFaceLossResets must be >= 0, was $maxFaceLossResets" }
        require(minBrightness <= 1f) { "minBrightness must be <= 1, was $minBrightness" }
        require(eyeClosedThreshold < eyeOpenThreshold) {
            "eyeClosedThreshold ($eyeClosedThreshold) must be < eyeOpenThreshold ($eyeOpenThreshold)"
        }
        require(headTurnYawDegrees > maxYawDegrees) {
            "headTurnYawDegrees ($headTurnYawDegrees) must exceed maxYawDegrees ($maxYawDegrees)"
        }
        require(neutralSmileThreshold < smileThreshold) {
            "neutralSmileThreshold ($neutralSmileThreshold) must be < smileThreshold ($smileThreshold)"
        }
        require(passiveRealProbThreshold in 0f..1f) {
            "passiveRealProbThreshold must be in [0,1], was $passiveRealProbThreshold"
        }
    }

    companion object {
        /** Java-friendly fluent builder. */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /** Mutable builder that preserves Kotlin defaults for any field left untouched. */
    class Builder {
        private var config = LivenessConfig()
        fun minFaceRatio(v: Float) = apply { config = config.copy(minFaceRatio = v) }
        fun maxYawDegrees(v: Float) = apply { config = config.copy(maxYawDegrees = v) }
        fun maxPitchDegrees(v: Float) = apply { config = config.copy(maxPitchDegrees = v) }
        fun challengeTypes(v: Set<ChallengeType>) = apply { config = config.copy(challengeTypes = v) }
        fun requiredChallengeCount(v: Int) = apply { config = config.copy(requiredChallengeCount = v) }
        fun challengeTimeoutMs(v: Long) = apply { config = config.copy(challengeTimeoutMs = v) }
        fun sessionTimeoutMs(v: Long) = apply { config = config.copy(sessionTimeoutMs = v) }
        fun maxFaceLossResets(v: Int) = apply { config = config.copy(maxFaceLossResets = v) }
        fun enablePassiveModel(v: Boolean) = apply { config = config.copy(enablePassiveModel = v) }
        fun minBrightness(v: Float) = apply { config = config.copy(minBrightness = v) }
        fun returnCapturedFrame(v: Boolean) = apply { config = config.copy(returnCapturedFrame = v) }
        fun passiveRealProbThreshold(v: Float) = apply { config = config.copy(passiveRealProbThreshold = v) }
        fun build(): LivenessConfig = config.also { it.validate() }
    }
}
