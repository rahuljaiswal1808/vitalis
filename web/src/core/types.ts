/**
 * Public types for Vitalis web liveness. Mirrors the Android `vitalis-core` API so the
 * two platforms behave identically. Framework-free: no DOM APIs are *called* here.
 */

/** A randomized micro-action the user performs to prove they are live (active tier). */
export type ChallengeType =
  | "blink"
  | "head_turn_left"
  | "head_turn_right"
  | "smile";

export const ALL_CHALLENGES: ChallengeType[] = [
  "blink",
  "head_turn_left",
  "head_turn_right",
  "smile",
];

/** Specific, non-generic failure reasons so the host page can guide the user. */
export type FailureReason =
  | "no_face"
  | "multiple_faces"
  | "face_too_small"
  | "face_not_frontal"
  | "low_light"
  | "challenge_timeout"
  | "challenge_failed"
  | "spoof_suspected"
  | "camera_error";

/** System/environment error codes (distinct from a liveness failure). */
export type LivenessErrorCode =
  | "no_camera"
  | "camera_permission_denied"
  | "camera_error"
  | "face_model_unavailable"
  | "passive_model_unavailable"
  | "internal";

export interface LivenessError {
  code: LivenessErrorCode;
  message: string;
  cause?: unknown;
}

/** Sentinel for an unavailable probability/measurement. */
export const UNKNOWN = -1;

/**
 * Neutral, per-frame face measurements consumed by the engine. A face-detection adapter
 * produces these; the engine reasons only about these numbers (keeps it DOM-free & testable).
 * Probabilities are in [0,1]; use {@link UNKNOWN} (-1) when unavailable.
 */
export interface FaceSignals {
  faceCount: number;
  /** Largest face box size as a fraction of the smaller frame dimension. */
  boundingBoxRatio: number;
  /** Yaw: negative = turned to subject's left, positive = right. */
  yawDegrees: number;
  /** Pitch: up/down nod. */
  pitchDegrees: number;
  /** Roll: in-plane tilt. */
  rollDegrees: number;
  leftEyeOpenProbability: number;
  rightEyeOpenProbability: number;
  smilingProbability: number;
  /** Average frame luma in [0,1], or {@link UNKNOWN}. */
  brightness: number;
  /** Monotonic timestamp in milliseconds. */
  timestampMs: number;
}

export function emptyFaceSignals(timestampMs: number): FaceSignals {
  return {
    faceCount: 0,
    boundingBoxRatio: 0,
    yawDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    leftEyeOpenProbability: UNKNOWN,
    rightEyeOpenProbability: UNKNOWN,
    smilingProbability: UNKNOWN,
    brightness: UNKNOWN,
    timestampMs,
  };
}

/** Observable phases of a session (discriminated union on `kind`). */
export type LivenessState =
  | { kind: "searching_face" }
  | { kind: "face_found" }
  | { kind: "awaiting_challenge"; challenge: ChallengeType }
  | { kind: "verifying" };

export function statesEqual(a: LivenessState, b: LivenessState): boolean {
  if (a.kind !== b.kind) return false;
  if (a.kind === "awaiting_challenge" && b.kind === "awaiting_challenge") {
    return a.challenge === b.challenge;
  }
  return true;
}

/**
 * Terminal outcome. `capturedFrame` is a JPEG Blob (or null when the host opts out /
 * a frame isn't available). The library never persists it — F6.
 */
export type LivenessResult =
  | { ok: true; capturedFrame: Blob | null; confidence: number }
  | { ok: false; reason: FailureReason };

/** Outputs of the pure engine; the orchestrator maps these to {@link LivenessResult}. */
export type EngineEvent =
  | { type: "state_changed"; state: LivenessState }
  | { type: "verified"; confidence: number }
  | { type: "rejected"; reason: FailureReason };

/** Callback surface for a session. All callbacks fire on the main thread. */
export interface LivenessListener {
  onStateChanged(state: LivenessState): void;
  onResult(result: LivenessResult): void;
  onError(error: LivenessError): void;
}
