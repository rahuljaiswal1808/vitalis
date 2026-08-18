package com.vitalis.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessEngineTest {

    private fun engine(config: LivenessConfig = LivenessConfig()) =
        LivenessEngine(config, random = Random(42))

    private fun blinkFrames(start: Long, e: LivenessEngine, rec: Recorder) {
        // open -> closed -> open satisfies the blink transition
        rec.feed(e.onFrame(TestSignals.validFrontal(start, leftEye = 0.95f, rightEye = 0.95f)))
        rec.feed(e.onFrame(TestSignals.validFrontal(start + 100, leftEye = 0.1f, rightEye = 0.1f)))
        rec.feed(e.onFrame(TestSignals.validFrontal(start + 200, leftEye = 0.95f, rightEye = 0.95f)))
    }

    @Test
    fun happyPath_blink_reachesVerified() {
        val e = engine(LivenessConfig(challengeTypes = setOf(ChallengeType.BLINK)))
        val rec = Recorder()
        rec.feed(e.start(0))
        // First valid frame -> FaceFound, second -> AwaitingChallenge(BLINK)
        rec.feed(e.onFrame(TestSignals.validFrontal(10)))
        rec.feed(e.onFrame(TestSignals.validFrontal(20)))
        blinkFrames(30, e, rec)

        val states = rec.states()
        assertTrue("saw SearchingFace", states.contains(LivenessState.SearchingFace))
        assertTrue("saw FaceFound", states.contains(LivenessState.FaceFound))
        assertTrue("saw AwaitingChallenge(BLINK)",
            states.contains(LivenessState.AwaitingChallenge(ChallengeType.BLINK)))
        assertTrue("saw Verifying", states.contains(LivenessState.Verifying))
        assertTrue("verified", rec.result() is EngineEvent.Verified)
    }

    @Test
    fun multipleFaces_rejectsImmediately() {
        val e = engine()
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.multiFace(10)))
        assertEquals(EngineEvent.Rejected(FailureReason.MULTIPLE_FACES), rec.result())
    }

    @Test
    fun tooSmallFace_timesOutAsFaceTooSmall() {
        val cfg = LivenessConfig(sessionTimeoutMs = 1000, challengeTimeoutMs = 1000, minFaceRatio = 0.25f)
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.validFrontal(10, ratio = 0.1f)))
        // push past session timeout with still-too-small face
        rec.feed(e.onFrame(TestSignals.validFrontal(2000, ratio = 0.1f)))
        assertEquals(EngineEvent.Rejected(FailureReason.FACE_TOO_SMALL), rec.result())
    }

    @Test
    fun noFace_timesOutAsNoFace() {
        val cfg = LivenessConfig(sessionTimeoutMs = 1000, challengeTimeoutMs = 1000)
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.noFace(10)))
        rec.feed(e.onTick(2000))
        assertEquals(EngineEvent.Rejected(FailureReason.NO_FACE), rec.result())
    }

    @Test
    fun challengeTimeout_whenUserDoesNothing() {
        val cfg = LivenessConfig(
            challengeTypes = setOf(ChallengeType.BLINK),
            challengeTimeoutMs = 500,
            sessionTimeoutMs = 10_000
        )
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.validFrontal(10)))
        rec.feed(e.onFrame(TestSignals.validFrontal(20))) // -> AwaitingChallenge
        rec.feed(e.onTick(1000))                          // exceeds challenge budget
        assertEquals(EngineEvent.Rejected(FailureReason.CHALLENGE_TIMEOUT), rec.result())
    }

    @Test
    fun staticOpenEyes_neverCountAsBlink_thenTimeout() {
        val cfg = LivenessConfig(
            challengeTypes = setOf(ChallengeType.BLINK),
            challengeTimeoutMs = 500, sessionTimeoutMs = 10_000
        )
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.validFrontal(10)))
        rec.feed(e.onFrame(TestSignals.validFrontal(20)))
        // Photo of open eyes: never closes
        repeat(3) { rec.feed(e.onFrame(TestSignals.validFrontal(30L + it, leftEye = 0.95f, rightEye = 0.95f))) }
        rec.feed(e.onTick(1000))
        assertEquals(EngineEvent.Rejected(FailureReason.CHALLENGE_TIMEOUT), rec.result())
    }

    @Test
    fun headTurnRight_passes() {
        val cfg = LivenessConfig(
            challengeTypes = setOf(ChallengeType.HEAD_TURN_RIGHT),
            headTurnYawDegrees = 22f
        )
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.validFrontal(10)))
        rec.feed(e.onFrame(TestSignals.validFrontal(20)))     // arm frontal + AwaitingChallenge
        rec.feed(e.onFrame(TestSignals.validFrontal(30)))     // still frontal -> armed
        rec.feed(e.onFrame(TestSignals.validFrontal(40, yaw = 30f))) // turned right
        assertTrue(rec.result() is EngineEvent.Verified)
    }

    @Test
    fun faceLossDuringChallenge_resetsThenRecovers() {
        val cfg = LivenessConfig(
            challengeTypes = setOf(ChallengeType.BLINK),
            maxFaceLossResets = 2, sessionTimeoutMs = 10_000, challengeTimeoutMs = 9_000
        )
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.validFrontal(10)))
        rec.feed(e.onFrame(TestSignals.validFrontal(20))) // AwaitingChallenge
        rec.feed(e.onFrame(TestSignals.noFace(30)))       // reset 1 -> SearchingFace
        val states = rec.states()
        assertTrue(states.last() == LivenessState.SearchingFace)
        assertTrue("no terminal yet", rec.result() == null)
    }

    @Test
    fun faceLossExceedsResets_failsChallenge() {
        val cfg = LivenessConfig(
            challengeTypes = setOf(ChallengeType.BLINK),
            maxFaceLossResets = 1, sessionTimeoutMs = 10_000, challengeTimeoutMs = 9_000
        )
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.validFrontal(10)))
        rec.feed(e.onFrame(TestSignals.validFrontal(20)))
        // reset 1 (ok), re-find, challenge again, reset 2 (exceeds)
        rec.feed(e.onFrame(TestSignals.noFace(30)))
        rec.feed(e.onFrame(TestSignals.validFrontal(40)))
        rec.feed(e.onFrame(TestSignals.validFrontal(50)))
        rec.feed(e.onFrame(TestSignals.noFace(60)))
        assertEquals(EngineEvent.Rejected(FailureReason.CHALLENGE_FAILED), rec.result())
    }

    @Test
    fun lowLight_gatesFaceDetection() {
        val cfg = LivenessConfig(minBrightness = 0.3f, sessionTimeoutMs = 1000, challengeTimeoutMs = 1000)
        val e = engine(cfg)
        val rec = Recorder()
        rec.feed(e.start(0))
        rec.feed(e.onFrame(TestSignals.validFrontal(10, brightness = 0.05f)))
        rec.feed(e.onTick(2000))
        assertEquals(EngineEvent.Rejected(FailureReason.LOW_LIGHT), rec.result())
    }
}
