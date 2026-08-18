package com.vitalis.core

/** Terminal outcome of a liveness session, delivered via [LivenessListener.onResult]. */
sealed class LivenessResult {
    /**
     * Liveness passed.
     *
     * @param capturedFrame the frame that satisfied verification, as JPEG bytes, or an empty
     *   array if the host opted out of frame return (F6 — the library never persists frames itself).
     * @param confidence combined active/passive confidence in [0,1].
     */
    data class Success(
        val capturedFrame: ByteArray,
        val confidence: Float
    ) : LivenessResult() {
        // ByteArray needs explicit equals/hashCode to behave in a data class.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return confidence == other.confidence && capturedFrame.contentEquals(other.capturedFrame)
        }

        override fun hashCode(): Int = 31 * capturedFrame.contentHashCode() + confidence.hashCode()
    }

    /** Liveness failed for a specific [reason]. */
    data class Failure(val reason: FailureReason) : LivenessResult()
}
