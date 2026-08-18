package com.vitalis.core

/**
 * Public entry point. Obtain an instance from `Vitalis.create(...)` in the
 * `vitalis-antispoof` module (which wires camera + face + engine together).
 *
 * Lifecycle: [start] once, receive callbacks on [LivenessListener], then the session
 * ends on a result or error. Call [stop] to cancel early (e.g. the user backed out).
 */
interface LivenessDetector {
    /** Begin a liveness session with [config], reporting to [listener]. */
    fun start(config: LivenessConfig, listener: LivenessListener)

    /** Cancel any in-flight session and release the camera. Safe to call multiple times. */
    fun stop()
}
