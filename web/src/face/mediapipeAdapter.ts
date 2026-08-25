import { FaceLandmarker, FilesetResolver, type FaceLandmarkerResult } from "@mediapipe/tasks-vision";
import { UNKNOWN, emptyFaceSignals } from "../core/types";
import type { FaceDetectorAdapter, FaceMetrics } from "./adapter";

export interface MediaPipeOptions {
  /**
   * Directory containing the tasks-vision wasm files. Point this at the copied
   * `@mediapipe/tasks-vision/wasm` folder you host, or a CDN you control.
   */
  wasmBasePath: string;
  /** URL of the `face_landmarker.task` model asset (host it yourself). */
  modelAssetPath: string;
  /** Max faces to detect. Keep >= 2 so the "multiple faces" rule (F2) can fire. */
  numFaces?: number;
}

/**
 * Face detector backed by MediaPipe Tasks Vision `FaceLandmarker`.
 *
 * Uses face blendshapes for eye-open / smile probabilities and the facial
 * transformation matrix for head pose — no custom model needed for the active tier.
 *
 * `@mediapipe/tasks-vision` is an optional peer dependency; install it in the host app.
 */
export async function createMediaPipeFaceDetector(
  options: MediaPipeOptions
): Promise<FaceDetectorAdapter> {
  const fileset = await FilesetResolver.forVisionTasks(options.wasmBasePath);
  const landmarker = await FaceLandmarker.createFromOptions(fileset, {
    baseOptions: { modelAssetPath: options.modelAssetPath, delegate: "GPU" },
    runningMode: "VIDEO",
    numFaces: options.numFaces ?? 2,
    outputFaceBlendshapes: true,
    outputFacialTransformationMatrixes: true,
  });
  return new MediaPipeFaceDetector(landmarker);
}

class MediaPipeFaceDetector implements FaceDetectorAdapter {
  constructor(private readonly landmarker: FaceLandmarker) {}

  detect(video: HTMLVideoElement, nowMs: number): FaceMetrics {
    const result = this.landmarker.detectForVideo(video, nowMs);
    const base = emptyFaceSignals(nowMs);
    const faceCount = result.faceLandmarks?.length ?? 0;
    if (faceCount === 0) return { ...base };

    const idx = largestFaceIndex(result);
    const landmarks = result.faceLandmarks[idx]!;
    let minX = 1, minY = 1, maxX = 0, maxY = 0;
    for (const p of landmarks) {
      if (p.x < minX) minX = p.x;
      if (p.y < minY) minY = p.y;
      if (p.x > maxX) maxX = p.x;
      if (p.y > maxY) maxY = p.y;
    }
    // Landmarks are normalized to frame dimensions; box fractions approximate the ratio.
    const boundingBoxRatio = Math.max(maxX - minX, maxY - minY);

    const blend = result.faceBlendshapes?.[idx];
    const b = (name: string): number => {
      const cat = blend?.categories.find((c) => c.categoryName === name);
      return cat ? cat.score : UNKNOWN;
    };
    const eyeBlinkL = b("eyeBlinkLeft");
    const eyeBlinkR = b("eyeBlinkRight");
    const smileL = b("mouthSmileLeft");
    const smileR = b("mouthSmileRight");

    const leftEyeOpenProbability = eyeBlinkL === UNKNOWN ? UNKNOWN : 1 - eyeBlinkL;
    const rightEyeOpenProbability = eyeBlinkR === UNKNOWN ? UNKNOWN : 1 - eyeBlinkR;
    const smilingProbability =
      smileL === UNKNOWN || smileR === UNKNOWN ? UNKNOWN : (smileL + smileR) / 2;

    const pose = eulerFromMatrix(result.facialTransformationMatrixes?.[idx]?.data);

    return {
      faceCount,
      boundingBoxRatio,
      yawDegrees: pose.yaw,
      pitchDegrees: pose.pitch,
      rollDegrees: pose.roll,
      leftEyeOpenProbability,
      rightEyeOpenProbability,
      smilingProbability,
      timestampMs: nowMs,
    };
  }

  close(): void {
    this.landmarker.close();
  }
}

function largestFaceIndex(result: FaceLandmarkerResult): number {
  if (!result.faceLandmarks || result.faceLandmarks.length <= 1) return 0;
  let best = 0;
  let bestArea = -1;
  result.faceLandmarks.forEach((lm, i) => {
    let minX = 1, minY = 1, maxX = 0, maxY = 0;
    for (const p of lm) {
      minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
      maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
    }
    const area = (maxX - minX) * (maxY - minY);
    if (area > bestArea) { bestArea = area; best = i; }
  });
  return best;
}

/**
 * Extract yaw/pitch/roll (degrees) from a 4x4 column-major transformation matrix.
 * Returns zeros when the matrix is absent.
 */
function eulerFromMatrix(m: number[] | undefined): { yaw: number; pitch: number; roll: number } {
  if (!m || m.length < 16) return { yaw: 0, pitch: 0, roll: 0 };
  // Column-major 4x4: rotation r[row][col] = m[col*4 + row].
  const r00 = m[0]!, r10 = m[1]!, r20 = m[2]!;
  const r21 = m[6]!, r22 = m[10]!;
  const toDeg = 180 / Math.PI;
  const pitch = Math.atan2(-r20, Math.hypot(r21, r22)) * toDeg;
  const yaw = Math.atan2(r10, r00) * toDeg;
  const roll = Math.atan2(r21, r22) * toDeg;
  return { yaw, pitch, roll };
}
