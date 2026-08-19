package com.vitalis.antispoof

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.vitalis.core.LivenessDetector

/**
 * Public factory for the Vitalis liveness pipeline.
 *
 * Java-friendly: every entry point is `@JvmStatic` with overloads, so Java callers write
 * `Vitalis.create(context, owner, previewView)` with no Kotlin default-argument gymnastics.
 *
 * Example (Kotlin):
 * ```
 * val detector = Vitalis.create(this, this, previewView)
 * detector.start(LivenessConfig(), myListener)
 * ```
 */
object Vitalis {

    /**
     * Create a detector that runs **active-challenge-only** liveness (no passive model).
     * This is the recommended v1 configuration (feature doc §10 step 4).
     *
     * @param previewView optional live-preview target; pass null for headless operation.
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ): LivenessDetector = VitalisLivenessDetector(
        context = context.applicationContext,
        lifecycleOwner = lifecycleOwner,
        previewView = previewView,
        passiveScorer = NoPassiveScorer
    )

    /**
     * Create a detector with an explicit [PassiveSpoofScorer] for the passive tier.
     * Supply your own validated model via [TfliteSpoofScorer.fromAsset] (§5, §10 step 5).
     * If the scorer's `isAvailable` is false, the detector transparently runs active-only.
     */
    @JvmStatic
    @JvmOverloads
    fun createWithPassiveScorer(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        passiveScorer: PassiveSpoofScorer,
        previewView: PreviewView? = null
    ): LivenessDetector = VitalisLivenessDetector(
        context = context.applicationContext,
        lifecycleOwner = lifecycleOwner,
        previewView = previewView,
        passiveScorer = passiveScorer
    )
}
