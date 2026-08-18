package com.vitalis.core

import kotlin.math.abs
import kotlin.random.Random

/**
 * Pure, deterministic liveness state machine (feature doc §7).
 *
 * It consumes a stream of [FaceSignals] plus a monotonic clock and emits [EngineEvent]s.
 * It performs the geometric gating (F2/F3) and the active-tier challenge-response logic
 * (F4 active). It has no Android, camera, ML Kit or pixel dependencies, so every
 * transition below is exercisable in a plain JVM unit test.
 *
 * Not thread-safe: call [start], [onFrame] and [onTick] from a single thread
 * (the orchestrator serializes analyzer callbacks onto one executor).
 */
class LivenessEngine(
    private val config: LivenessConfig,
    private val random: Random = Random.Default
) {
    init {
        config.validate()
    }

    private enum class Phase { IDLE, SEARCHING, FACE_FOUND, AWAITING_CHALLENGE, VERIFYING, DONE }

    private var phase: Phase = Phase.IDLE
    private var sessionStartMs: Long = 0L

    private var challengeQueue: List<ChallengeType> = emptyList()
    private var challengeIndex: Int = 0
    private var challengeStartMs: Long = 0L
    private var resetCount: Int = 0

    /** Whether the current face is invalid this frame, and why (for good timeout reasons). */
    private var lastInvalidReason: FailureReason = FailureReason.NO_FACE

    /** Per-challenge progress sub-state (transition tracking for blink/smile). */
    private var armed: Boolean = false           // saw the "before" side of a transition
    private var extremeReached: Boolean = false   // saw the peak (eyes closed / head fully turned)
    private val challengeQuality = mutableListOf<Float>()

    /** The most recent phase; used to emit StateChanged only on genuine transitions. */
    private var lastEmittedState: LivenessState? = null

    val currentChallenge: ChallengeType?
        get() = challengeQueue.getOrNull(challengeIndex)

    /** Begin a session at [nowMs]. Returns the initial event(s). */
    fun start(nowMs: Long): List<EngineEvent> {
        phase = Phase.SEARCHING
        sessionStartMs = nowMs
        challengeIndex = 0
        resetCount = 0
        challengeQuality.clear()
        lastInvalidReason = FailureReason.NO_FACE
        challengeQueue = buildChallengeQueue()
        lastEmittedState = null
        return emit(LivenessState.SearchingFace)
    }

    /** Feed one analyzed frame. Returns any events produced by the transition. */
    fun onFrame(signals: FaceSignals): List<EngineEvent> {
        if (phase == Phase.IDLE || phase == Phase.DONE) return emptyList()

        // Session-wide timeout takes precedence in every active phase.
        timeoutFailure(signals.timestampMs)?.let { return reject(it) }

        // More than one face is an immediate reject in any phase (F2).
        if (signals.faceCount > 1) return reject(FailureReason.MULTIPLE_FACES)

        return when (phase) {
            Phase.SEARCHING, Phase.FACE_FOUND -> handleSearching(signals)
            Phase.AWAITING_CHALLENGE -> handleChallenge(signals)
            Phase.VERIFYING -> handleVerifying()
            else -> emptyList()
        }
    }

    /**
     * Drive time-based transitions when frames stall (camera hiccup). The orchestrator
     * calls this on a timer so a frozen feed still hits [FailureReason.CHALLENGE_TIMEOUT].
     */
    fun onTick(nowMs: Long): List<EngineEvent> {
        if (phase == Phase.IDLE || phase == Phase.DONE) return emptyList()
        return timeoutFailure(nowMs)?.let { reject(it) } ?: emptyList()
    }

    // --- phase handlers ---------------------------------------------------

    private fun handleSearching(signals: FaceSignals): List<EngineEvent> {
        val validity = faceValidity(signals, expectingTurn = false)
        if (validity != null) {
            lastInvalidReason = validity
            // Never emerged from searching yet — stay put, host shows guidance from state.
            if (phase == Phase.FACE_FOUND) {
                phase = Phase.SEARCHING
                return emit(LivenessState.SearchingFace)
            }
            return emptyList()
        }

        // Valid face this frame.
        return when (phase) {
            Phase.SEARCHING -> {
                phase = Phase.FACE_FOUND
                emit(LivenessState.FaceFound)
            }
            Phase.FACE_FOUND -> issueNextChallenge(signals)
            else -> emptyList()
        }
    }

    private fun handleChallenge(signals: FaceSignals): List<EngineEvent> {
        val type = currentChallenge ?: return advanceAfterChallenge(1f)
        val expectingTurn = type == ChallengeType.HEAD_TURN_LEFT || type == ChallengeType.HEAD_TURN_RIGHT

        // Face lost / invalid during a challenge → tolerate a few resets (§7), else fail.
        val validity = faceValidity(signals, expectingTurn = expectingTurn)
        if (validity != null && !expectingTurn) {
            // For turn challenges a non-frontal face is expected, so don't treat it as loss there.
            if (validity == FailureReason.NO_FACE || validity == FailureReason.MULTIPLE_FACES ||
                validity == FailureReason.FACE_TOO_SMALL || validity == FailureReason.LOW_LIGHT
            ) {
                return faceLostDuringChallenge()
            }
        }
        if (signals.faceCount == 0) return faceLostDuringChallenge()
        if (signals.faceCount > 1) return reject(FailureReason.MULTIPLE_FACES)

        val quality = evaluateChallenge(type, signals)
        return if (quality != null) advanceAfterChallenge(quality) else emptyList()
    }

    private fun handleVerifying(): List<EngineEvent> {
        // Active tier complete. Passive scoring (if any) is applied by the orchestrator.
        phase = Phase.DONE
        val confidence = if (challengeQuality.isEmpty()) {
            0.85f
        } else {
            challengeQuality.average().toFloat().coerceIn(0f, 1f)
        }
        return listOf(EngineEvent.Verified(confidence))
    }

    // --- challenge evaluation --------------------------------------------

    /** @return a quality score in [0,1] when the challenge is satisfied this frame, else null. */
    private fun evaluateChallenge(type: ChallengeType, s: FaceSignals): Float? = when (type) {
        ChallengeType.BLINK -> evaluateBlink(s)
        ChallengeType.SMILE -> evaluateSmile(s)
        ChallengeType.HEAD_TURN_LEFT -> evaluateTurn(s, toRight = false)
        ChallengeType.HEAD_TURN_RIGHT -> evaluateTurn(s, toRight = true)
    }

    private fun evaluateBlink(s: FaceSignals): Float? {
        val l = s.leftEyeOpenProbability
        val r = s.rightEyeOpenProbability
        if (l == FaceSignals.UNKNOWN || r == FaceSignals.UNKNOWN) return null
        val bothOpen = l > config.eyeOpenThreshold && r > config.eyeOpenThreshold
        val bothClosed = l < config.eyeClosedThreshold && r < config.eyeClosedThreshold
        // Require open -> closed -> open so a static photo of open eyes can't pass.
        if (!armed) {
            if (bothOpen) armed = true
            return null
        }
        if (!extremeReached) {
            if (bothClosed) extremeReached = true
            return null
        }
        return if (bothOpen) {
            // Deeper close = higher quality.
            val depth = 1f - ((l + r) / 2f).coerceIn(0f, 1f)
            (0.5f + 0.5f * depth).coerceIn(0f, 1f)
        } else null
    }

    private fun evaluateSmile(s: FaceSignals): Float? {
        val p = s.smilingProbability
        if (p == FaceSignals.UNKNOWN) return null
        if (!armed) {
            if (p < config.neutralSmileThreshold) armed = true
            return null
        }
        return if (p > config.smileThreshold) p.coerceIn(0f, 1f) else null
    }

    private fun evaluateTurn(s: FaceSignals, toRight: Boolean): Float? {
        val yaw = s.yawDegrees
        val threshold = config.headTurnYawDegrees
        if (!armed) {
            // Must start roughly frontal so a pre-turned photo can't pass.
            if (abs(yaw) <= config.maxYawDegrees) armed = true
            return null
        }
        val reached = if (toRight) yaw >= threshold else yaw <= -threshold
        if (reached) {
            val margin = ((abs(yaw) - threshold) / threshold).coerceIn(0f, 1f)
            return (0.6f + 0.4f * margin).coerceIn(0f, 1f)
        }
        return null
    }

    // --- transitions ------------------------------------------------------

    private fun issueNextChallenge(signals: FaceSignals): List<EngineEvent> {
        val type = currentChallenge ?: return startVerifying()
        phase = Phase.AWAITING_CHALLENGE
        challengeStartMs = signals.timestampMs
        armed = false
        extremeReached = false
        return emit(LivenessState.AwaitingChallenge(type))
    }

    private fun advanceAfterChallenge(quality: Float): List<EngineEvent> {
        challengeQuality.add(quality)
        challengeIndex++
        resetCount = 0
        armed = false
        extremeReached = false
        return if (challengeIndex >= challengeQueue.size) {
            startVerifying()
        } else {
            val next = challengeQueue[challengeIndex]
            phase = Phase.AWAITING_CHALLENGE
            challengeStartMs = lastTimestamp
            emit(LivenessState.AwaitingChallenge(next))
        }
    }

    private fun startVerifying(): List<EngineEvent> {
        phase = Phase.VERIFYING
        return emit(LivenessState.Verifying) + handleVerifying()
    }

    private fun faceLostDuringChallenge(): List<EngineEvent> {
        resetCount++
        if (resetCount > config.maxFaceLossResets) {
            return reject(FailureReason.CHALLENGE_FAILED)
        }
        // Reset to searching; the challenge will be re-issued once a valid face returns.
        phase = Phase.SEARCHING
        armed = false
        extremeReached = false
        return emit(LivenessState.SearchingFace)
    }

    // --- helpers ----------------------------------------------------------

    private var lastTimestamp: Long = 0L

    private fun faceValidity(s: FaceSignals, expectingTurn: Boolean): FailureReason? {
        lastTimestamp = s.timestampMs
        if (config.minBrightness > 0f && s.brightness != FaceSignals.UNKNOWN &&
            s.brightness < config.minBrightness
        ) {
            return FailureReason.LOW_LIGHT
        }
        if (s.faceCount == 0) return FailureReason.NO_FACE
        if (s.faceCount > 1) return FailureReason.MULTIPLE_FACES
        if (s.boundingBoxRatio < config.minFaceRatio) return FailureReason.FACE_TOO_SMALL
        if (!expectingTurn) {
            if (abs(s.yawDegrees) > config.maxYawDegrees) return FailureReason.FACE_NOT_FRONTAL
            if (abs(s.pitchDegrees) > config.maxPitchDegrees) return FailureReason.FACE_NOT_FRONTAL
        }
        return null
    }

    private fun timeoutFailure(nowMs: Long): FailureReason? {
        if (phase == Phase.IDLE || phase == Phase.DONE) return null
        if (nowMs - sessionStartMs > config.sessionTimeoutMs) {
            return sessionTimeoutReason()
        }
        if (phase == Phase.AWAITING_CHALLENGE && nowMs - challengeStartMs > config.challengeTimeoutMs) {
            return FailureReason.CHALLENGE_TIMEOUT
        }
        return null
    }

    private fun sessionTimeoutReason(): FailureReason = when (phase) {
        Phase.SEARCHING, Phase.FACE_FOUND -> lastInvalidReason
        Phase.AWAITING_CHALLENGE, Phase.VERIFYING -> FailureReason.CHALLENGE_TIMEOUT
        else -> FailureReason.CHALLENGE_TIMEOUT
    }

    private fun buildChallengeQueue(): List<ChallengeType> {
        val pool = config.challengeTypes.toList()
        return (0 until config.requiredChallengeCount).map { pool[random.nextInt(pool.size)] }
    }

    private fun reject(reason: FailureReason): List<EngineEvent> {
        phase = Phase.DONE
        return listOf(EngineEvent.Rejected(reason))
    }

    private fun emit(state: LivenessState): List<EngineEvent> {
        if (state == lastEmittedState) return emptyList()
        lastEmittedState = state
        return listOf(EngineEvent.StateChanged(state))
    }
}
