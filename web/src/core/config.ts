import type { ChallengeType } from "./types";

/**
 * Tunable parameters for a liveness session. Same fields and defaults as Android
 * `LivenessConfig`. Pass a partial object to {@link resolveConfig} to fill defaults
 * and validate.
 */
export interface LivenessConfig {
  /** Min face box size as a fraction of the smaller frame dimension. */
  minFaceRatio: number;
  /** Max absolute yaw for a "frontal" face, degrees. */
  maxYawDegrees: number;
  /** Max absolute pitch for a "frontal" face, degrees. */
  maxPitchDegrees: number;
  /** Pool of challenges; each required challenge is chosen at random from it. */
  challengeTypes: ChallengeType[];
  /** How many challenges must pass before verifying. */
  requiredChallengeCount: number;
  /** Per-challenge time budget (ms). */
  challengeTimeoutMs: number;
  /** Total session budget (ms). */
  sessionTimeoutMs: number;
  /** Face-loss events tolerated during a challenge before failing. */
  maxFaceLossResets: number;
  /** Whether the passive tier runs (if a scorer is available). */
  enablePassiveModel: boolean;
  /** Min average frame brightness [0,1] to attempt liveness; <=0 disables the gate. */
  minBrightness: number;
  /** Eye-open prob below which an eye is "closed". */
  eyeClosedThreshold: number;
  /** Eye-open prob above which an eye is "open". */
  eyeOpenThreshold: number;
  /** Yaw magnitude that satisfies a head-turn. */
  headTurnYawDegrees: number;
  /** Smile prob that satisfies SMILE. */
  smileThreshold: number;
  /** Smile prob the face must first drop below to arm SMILE. */
  neutralSmileThreshold: number;
  /** Whether Success carries the verified frame as a JPEG Blob. */
  returnCapturedFrame: boolean;
  /** Passive tier: min "real" probability [0,1] to accept. */
  passiveRealProbThreshold: number;
}

export const DEFAULT_CONFIG: LivenessConfig = {
  minFaceRatio: 0.25,
  maxYawDegrees: 15,
  maxPitchDegrees: 15,
  challengeTypes: ["blink"],
  requiredChallengeCount: 1,
  challengeTimeoutMs: 5000,
  sessionTimeoutMs: 30000,
  maxFaceLossResets: 3,
  enablePassiveModel: true,
  minBrightness: 0.15,
  eyeClosedThreshold: 0.35,
  eyeOpenThreshold: 0.65,
  headTurnYawDegrees: 22,
  smileThreshold: 0.7,
  neutralSmileThreshold: 0.3,
  returnCapturedFrame: true,
  passiveRealProbThreshold: 0.5,
};

/** @throws Error with a specific message on invalid configuration. */
export function validateConfig(c: LivenessConfig): void {
  const inRange = (v: number, lo: number, hi: number) => v >= lo && v <= hi;
  if (!inRange(c.minFaceRatio, 0, 1)) throw new Error(`minFaceRatio must be in [0,1], was ${c.minFaceRatio}`);
  if (!inRange(c.maxYawDegrees, 0, 90)) throw new Error(`maxYawDegrees must be in [0,90], was ${c.maxYawDegrees}`);
  if (!inRange(c.maxPitchDegrees, 0, 90)) throw new Error(`maxPitchDegrees must be in [0,90], was ${c.maxPitchDegrees}`);
  if (c.challengeTypes.length === 0) throw new Error("challengeTypes must not be empty");
  if (c.requiredChallengeCount < 1) throw new Error(`requiredChallengeCount must be >= 1, was ${c.requiredChallengeCount}`);
  if (c.challengeTimeoutMs <= 0) throw new Error(`challengeTimeoutMs must be > 0, was ${c.challengeTimeoutMs}`);
  if (c.sessionTimeoutMs < c.challengeTimeoutMs)
    throw new Error(`sessionTimeoutMs (${c.sessionTimeoutMs}) must be >= challengeTimeoutMs (${c.challengeTimeoutMs})`);
  if (c.maxFaceLossResets < 0) throw new Error(`maxFaceLossResets must be >= 0, was ${c.maxFaceLossResets}`);
  if (c.minBrightness > 1) throw new Error(`minBrightness must be <= 1, was ${c.minBrightness}`);
  if (c.eyeClosedThreshold >= c.eyeOpenThreshold)
    throw new Error(`eyeClosedThreshold (${c.eyeClosedThreshold}) must be < eyeOpenThreshold (${c.eyeOpenThreshold})`);
  if (c.headTurnYawDegrees <= c.maxYawDegrees)
    throw new Error(`headTurnYawDegrees (${c.headTurnYawDegrees}) must exceed maxYawDegrees (${c.maxYawDegrees})`);
  if (c.neutralSmileThreshold >= c.smileThreshold)
    throw new Error(`neutralSmileThreshold (${c.neutralSmileThreshold}) must be < smileThreshold (${c.smileThreshold})`);
  if (!inRange(c.passiveRealProbThreshold, 0, 1))
    throw new Error(`passiveRealProbThreshold must be in [0,1], was ${c.passiveRealProbThreshold}`);
}

/** Merge a partial config over defaults and validate. */
export function resolveConfig(partial: Partial<LivenessConfig> = {}): LivenessConfig {
  const config = { ...DEFAULT_CONFIG, ...partial };
  validateConfig(config);
  return config;
}
