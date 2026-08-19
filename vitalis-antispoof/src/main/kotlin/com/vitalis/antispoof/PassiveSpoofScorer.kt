package com.vitalis.antispoof

import androidx.camera.core.ImageProxy

/**
 * Passive anti-spoof tier (feature doc §4/F4, §5): a per-frame classifier that scores
 * how likely the frame shows a **real, live face** vs. a print/screen replay, using
 * texture/frequency cues a challenge can't cover.
 *
 * The active challenge tier works without this. Provide an implementation only once you
 * have a model **with an eval number you can quote** (§5) — do not ship a guess.
 */
interface PassiveSpoofScorer {
    /** True only when a real model is loaded and ready. When false, the orchestrator runs active-only. */
    val isAvailable: Boolean

    /**
     * @return probability in [0,1] that [image] is a genuine live face. The image is open;
     *   do not close it (the orchestrator owns its lifecycle).
     */
    fun scoreRealProbability(image: ImageProxy): Float

    fun close()
}

/**
 * Default no-op scorer used when no model is supplied. [isAvailable] is false so the
 * orchestrator transparently falls back to active-challenge-only liveness.
 */
object NoPassiveScorer : PassiveSpoofScorer {
    override val isAvailable: Boolean = false
    override fun scoreRealProbability(image: ImageProxy): Float = 1f
    override fun close() {}
}
