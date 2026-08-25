import type { FaceSignals } from "../core/types";

/** Everything a detector produces per frame except the brightness (added by the orchestrator). */
export type FaceMetrics = Omit<FaceSignals, "brightness">;

/**
 * Pluggable face-detection backend. Ship the MediaPipe adapter (see
 * {@link createMediaPipeFaceDetector}) or implement this against any detector that can
 * give per-eye open probability, a smile probability, and head yaw/pitch.
 */
export interface FaceDetectorAdapter {
  /** Synchronous per-frame detect. `nowMs` must increase monotonically. */
  detect(video: HTMLVideoElement, nowMs: number): FaceMetrics;
  close(): void;
}
