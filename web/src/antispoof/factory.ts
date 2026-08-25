import type { LivenessConfig } from "../core/config";
import type { LivenessListener } from "../core/types";
import { createMediaPipeFaceDetector, type MediaPipeOptions } from "../face/mediapipeAdapter";
import { VitalisLivenessDetector, type DetectorOptions } from "./detector";
import type { PassiveSpoofScorer } from "./passiveScorer";

/**
 * Create a detector from an already-constructed face adapter (and optional passive scorer).
 * Use this when you manage the MediaPipe/model lifecycle yourself.
 */
export function createLivenessDetector(options: DetectorOptions): VitalisLivenessDetector {
  return new VitalisLivenessDetector(options);
}

export interface AutoLivenessOptions extends MediaPipeOptions {
  video: HTMLVideoElement;
  passiveScorer?: PassiveSpoofScorer;
}

/**
 * Convenience: build a MediaPipe face detector and return a ready detector.
 * You still call `.start(config, listener)` yourself.
 */
export async function createMediaPipeLivenessDetector(
  options: AutoLivenessOptions
): Promise<VitalisLivenessDetector> {
  const faceDetector = await createMediaPipeFaceDetector(options);
  return new VitalisLivenessDetector({
    video: options.video,
    faceDetector,
    passiveScorer: options.passiveScorer,
  });
}

export type { LivenessConfig, LivenessListener };
