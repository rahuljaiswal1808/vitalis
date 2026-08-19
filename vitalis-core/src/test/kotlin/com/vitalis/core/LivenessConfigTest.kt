package com.vitalis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LivenessConfigTest {
    @Test fun defaultsAreValid() { LivenessConfig().validate() }

    @Test fun rejectsBadFaceRatio() {
        assertThrows(IllegalArgumentException::class.java) {
            LivenessConfig(minFaceRatio = 1.5f).validate()
        }
    }

    @Test fun rejectsEmptyChallengeSet() {
        assertThrows(IllegalArgumentException::class.java) {
            LivenessConfig(challengeTypes = emptySet()).validate()
        }
    }

    @Test fun rejectsSessionShorterThanChallenge() {
        assertThrows(IllegalArgumentException::class.java) {
            LivenessConfig(sessionTimeoutMs = 100, challengeTimeoutMs = 5000).validate()
        }
    }

    @Test fun rejectsEyeThresholdInversion() {
        assertThrows(IllegalArgumentException::class.java) {
            LivenessConfig(eyeClosedThreshold = 0.8f, eyeOpenThreshold = 0.2f).validate()
        }
    }

    @Test fun builderPreservesDefaults() {
        val c = LivenessConfig.builder().minFaceRatio(0.3f).build()
        assertEquals(0.3f, c.minFaceRatio)
        assertEquals(LivenessConfig().maxYawDegrees, c.maxYawDegrees)
    }
}
