import type { LivenessConfig } from "./config";
import { validateConfig } from "./config";
import {
  type ChallengeType,
  type EngineEvent,
  type FaceSignals,
  type FailureReason,
  type LivenessState,
  statesEqual,
  UNKNOWN,
} from "./types";

type Phase =
  | "idle"
  | "searching"
  | "face_found"
  | "awaiting_challenge"
  | "verifying"
  | "done";

/** Face-invalidity reason for a single frame (null = valid). */
type Invalid = Exclude<
  FailureReason,
  "challenge_timeout" | "challenge_failed" | "spoof_suspected" | "camera_error"
> | null;

/**
 * Pure, deterministic liveness state machine — a direct port of the Android
 * `LivenessEngine`. Consumes {@link FaceSignals} + a monotonic clock, emits
 * {@link EngineEvent}s. No DOM, no camera, no model — fully unit-testable in node.
 *
 * Single-threaded by contract: drive `start`, `onFrame`, `onTick` from one place.
 */
export class LivenessEngine {
  private readonly config: LivenessConfig;
  private readonly random: () => number;

  private phase: Phase = "idle";
  private sessionStartMs = 0;
  private challengeQueue: ChallengeType[] = [];
  private challengeIndex = 0;
  private challengeStartMs = 0;
  private resetCount = 0;
  private lastInvalidReason: Invalid = "no_face";
  private armed = false;
  private extremeReached = false;
  private challengeQuality: number[] = [];
  private lastEmittedState: LivenessState | null = null;
  private lastTimestamp = 0;

  constructor(config: LivenessConfig, random: () => number = Math.random) {
    validateConfig(config);
    this.config = config;
    this.random = random;
  }

  get currentChallenge(): ChallengeType | undefined {
    return this.challengeQueue[this.challengeIndex];
  }

  start(nowMs: number): EngineEvent[] {
    this.phase = "searching";
    this.sessionStartMs = nowMs;
    this.challengeIndex = 0;
    this.resetCount = 0;
    this.challengeQuality = [];
    this.lastInvalidReason = "no_face";
    this.challengeQueue = this.buildChallengeQueue();
    this.lastEmittedState = null;
    return this.emit({ kind: "searching_face" });
  }

  onFrame(signals: FaceSignals): EngineEvent[] {
    if (this.phase === "idle" || this.phase === "done") return [];

    const timeout = this.timeoutFailure(signals.timestampMs);
    if (timeout) return this.reject(timeout);

    // More than one face is an immediate reject in any phase (F2).
    if (signals.faceCount > 1) return this.reject("multiple_faces");

    switch (this.phase) {
      case "searching":
      case "face_found":
        return this.handleSearching(signals);
      case "awaiting_challenge":
        return this.handleChallenge(signals);
      case "verifying":
        return this.handleVerifying();
      default:
        return [];
    }
  }

  /** Drive time-based transitions when frames stall (camera hiccup). */
  onTick(nowMs: number): EngineEvent[] {
    if (this.phase === "idle" || this.phase === "done") return [];
    const reason = this.timeoutFailure(nowMs);
    return reason ? this.reject(reason) : [];
  }

  // --- phase handlers ---------------------------------------------------

  private handleSearching(signals: FaceSignals): EngineEvent[] {
    const validity = this.faceValidity(signals, false);
    if (validity !== null) {
      this.lastInvalidReason = validity;
      if (this.phase === "face_found") {
        this.phase = "searching";
        return this.emit({ kind: "searching_face" });
      }
      return [];
    }
    if (this.phase === "searching") {
      this.phase = "face_found";
      return this.emit({ kind: "face_found" });
    }
    if (this.phase === "face_found") {
      return this.issueNextChallenge(signals);
    }
    return [];
  }

  private handleChallenge(signals: FaceSignals): EngineEvent[] {
    const type = this.currentChallenge;
    if (!type) return this.advanceAfterChallenge(1);
    const expectingTurn = type === "head_turn_left" || type === "head_turn_right";

    const validity = this.faceValidity(signals, expectingTurn);
    if (
      validity !== null &&
      !expectingTurn &&
      (validity === "no_face" ||
        validity === "multiple_faces" ||
        validity === "face_too_small" ||
        validity === "low_light")
    ) {
      return this.faceLostDuringChallenge();
    }
    if (signals.faceCount === 0) return this.faceLostDuringChallenge();
    if (signals.faceCount > 1) return this.reject("multiple_faces");

    const quality = this.evaluateChallenge(type, signals);
    return quality !== null ? this.advanceAfterChallenge(quality) : [];
  }

  private handleVerifying(): EngineEvent[] {
    this.phase = "done";
    const confidence =
      this.challengeQuality.length === 0
        ? 0.85
        : clamp(avg(this.challengeQuality), 0, 1);
    return [{ type: "verified", confidence }];
  }

  // --- challenge evaluation --------------------------------------------

  private evaluateChallenge(type: ChallengeType, s: FaceSignals): number | null {
    switch (type) {
      case "blink":
        return this.evaluateBlink(s);
      case "smile":
        return this.evaluateSmile(s);
      case "head_turn_left":
        return this.evaluateTurn(s, false);
      case "head_turn_right":
        return this.evaluateTurn(s, true);
    }
  }

  private evaluateBlink(s: FaceSignals): number | null {
    const l = s.leftEyeOpenProbability;
    const r = s.rightEyeOpenProbability;
    if (l === UNKNOWN || r === UNKNOWN) return null;
    const bothOpen = l > this.config.eyeOpenThreshold && r > this.config.eyeOpenThreshold;
    const bothClosed = l < this.config.eyeClosedThreshold && r < this.config.eyeClosedThreshold;
    if (!this.armed) {
      if (bothOpen) this.armed = true;
      return null;
    }
    if (!this.extremeReached) {
      if (bothClosed) this.extremeReached = true;
      return null;
    }
    if (bothOpen) {
      const depth = 1 - clamp((l + r) / 2, 0, 1);
      return clamp(0.5 + 0.5 * depth, 0, 1);
    }
    return null;
  }

  private evaluateSmile(s: FaceSignals): number | null {
    const p = s.smilingProbability;
    if (p === UNKNOWN) return null;
    if (!this.armed) {
      if (p < this.config.neutralSmileThreshold) this.armed = true;
      return null;
    }
    return p > this.config.smileThreshold ? clamp(p, 0, 1) : null;
  }

  private evaluateTurn(s: FaceSignals, toRight: boolean): number | null {
    const yaw = s.yawDegrees;
    const threshold = this.config.headTurnYawDegrees;
    if (!this.armed) {
      if (Math.abs(yaw) <= this.config.maxYawDegrees) this.armed = true;
      return null;
    }
    const reached = toRight ? yaw >= threshold : yaw <= -threshold;
    if (reached) {
      const margin = clamp((Math.abs(yaw) - threshold) / threshold, 0, 1);
      return clamp(0.6 + 0.4 * margin, 0, 1);
    }
    return null;
  }

  // --- transitions ------------------------------------------------------

  private issueNextChallenge(signals: FaceSignals): EngineEvent[] {
    const type = this.currentChallenge;
    if (!type) return this.startVerifying();
    this.phase = "awaiting_challenge";
    this.challengeStartMs = signals.timestampMs;
    this.armed = false;
    this.extremeReached = false;
    return this.emit({ kind: "awaiting_challenge", challenge: type });
  }

  private advanceAfterChallenge(quality: number): EngineEvent[] {
    this.challengeQuality.push(quality);
    this.challengeIndex++;
    this.resetCount = 0;
    this.armed = false;
    this.extremeReached = false;
    if (this.challengeIndex >= this.challengeQueue.length) {
      return this.startVerifying();
    }
    const next = this.challengeQueue[this.challengeIndex]!;
    this.phase = "awaiting_challenge";
    this.challengeStartMs = this.lastTimestamp;
    return this.emit({ kind: "awaiting_challenge", challenge: next });
  }

  private startVerifying(): EngineEvent[] {
    this.phase = "verifying";
    return [...this.emit({ kind: "verifying" }), ...this.handleVerifying()];
  }

  private faceLostDuringChallenge(): EngineEvent[] {
    this.resetCount++;
    if (this.resetCount > this.config.maxFaceLossResets) {
      return this.reject("challenge_failed");
    }
    this.phase = "searching";
    this.armed = false;
    this.extremeReached = false;
    return this.emit({ kind: "searching_face" });
  }

  // --- helpers ----------------------------------------------------------

  private faceValidity(s: FaceSignals, expectingTurn: boolean): Invalid {
    this.lastTimestamp = s.timestampMs;
    if (this.config.minBrightness > 0 && s.brightness !== UNKNOWN && s.brightness < this.config.minBrightness) {
      return "low_light";
    }
    if (s.faceCount === 0) return "no_face";
    if (s.faceCount > 1) return "multiple_faces";
    if (s.boundingBoxRatio < this.config.minFaceRatio) return "face_too_small";
    if (!expectingTurn) {
      if (Math.abs(s.yawDegrees) > this.config.maxYawDegrees) return "face_not_frontal";
      if (Math.abs(s.pitchDegrees) > this.config.maxPitchDegrees) return "face_not_frontal";
    }
    return null;
  }

  private timeoutFailure(nowMs: number): FailureReason | null {
    if (this.phase === "idle" || this.phase === "done") return null;
    if (nowMs - this.sessionStartMs > this.config.sessionTimeoutMs) {
      return this.sessionTimeoutReason();
    }
    if (this.phase === "awaiting_challenge" && nowMs - this.challengeStartMs > this.config.challengeTimeoutMs) {
      return "challenge_timeout";
    }
    return null;
  }

  private sessionTimeoutReason(): FailureReason {
    switch (this.phase) {
      case "searching":
      case "face_found":
        return this.lastInvalidReason ?? "no_face";
      default:
        return "challenge_timeout";
    }
  }

  private buildChallengeQueue(): ChallengeType[] {
    const pool = this.config.challengeTypes;
    const out: ChallengeType[] = [];
    for (let i = 0; i < this.config.requiredChallengeCount; i++) {
      out.push(pool[Math.floor(this.random() * pool.length)]!);
    }
    return out;
  }

  private reject(reason: FailureReason): EngineEvent[] {
    this.phase = "done";
    return [{ type: "rejected", reason }];
  }

  private emit(state: LivenessState): EngineEvent[] {
    if (this.lastEmittedState && statesEqual(state, this.lastEmittedState)) return [];
    this.lastEmittedState = state;
    return [{ type: "state_changed", state }];
  }
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}
function avg(xs: number[]): number {
  return xs.reduce((a, b) => a + b, 0) / xs.length;
}
