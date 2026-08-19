package com.vitalis.core

/**
 * Callback surface for a liveness session.
 *
 * Deliberately a plain listener interface (no `suspend`, no coroutines) so Java callers
 * can implement it directly — the host app is Kotlin *and* Java (feature doc §6).
 * All callbacks are delivered on the main thread.
 */
interface LivenessListener {
    /** Phase transitions (§7). Use these to drive UI prompts. */
    fun onStateChanged(state: LivenessState)

    /** Terminal success/failure. After this, the session is finished. */
    fun onResult(result: LivenessResult)

    /** System/environment errors that prevented the session from running. */
    fun onError(error: LivenessError)
}
