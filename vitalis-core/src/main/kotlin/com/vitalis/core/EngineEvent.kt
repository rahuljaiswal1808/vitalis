package com.vitalis.core

/**
 * Outputs of [LivenessEngine], consumed by the orchestrator in `vitalis-antispoof`.
 *
 * The engine is byte-free: on [Verified] the orchestrator attaches the most recent
 * captured frame (and optionally runs the passive spoof model) before emitting the
 * public [LivenessResult.Success]. This keeps all pixel/model concerns out of core.
 */
sealed class EngineEvent {
    /** The observable [LivenessState] changed. */
    data class StateChanged(val state: LivenessState) : EngineEvent()

    /** Active-tier liveness passed with the given [confidence] in [0,1]. */
    data class Verified(val confidence: Float) : EngineEvent()

    /** The session ended in failure with a specific [reason]. */
    data class Rejected(val reason: FailureReason) : EngineEvent()
}
