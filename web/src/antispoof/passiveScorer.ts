/**
 * Passive anti-spoof tier: a per-frame classifier scoring how likely the frame shows a
 * real, live face vs. a print/screen replay. The active challenge tier works without this.
 *
 * No validated model ships with Vitalis (see feature spec §5) — provide your own only once
 * you can quote an eval number. Use {@link customPassiveScorer} to plug in a tfjs /
 * onnxruntime-web model without adding a heavy dependency to this library.
 */
export interface PassiveSpoofScorer {
  /** False ⇒ orchestrator runs active-only. */
  readonly isAvailable: boolean;
  /** @returns probability in [0,1] that the frame is a genuine live face. */
  scoreRealProbability(frame: HTMLCanvasElement): number | Promise<number>;
  close(): void;
}

/** Default: no passive scoring. Active-challenge-only liveness. */
export const NoPassiveScorer: PassiveSpoofScorer = {
  isAvailable: false,
  scoreRealProbability: () => 1,
  close: () => {},
};

/**
 * Wrap a user-supplied scoring function (e.g. a tfjs/onnx model you loaded and evaluated)
 * as a {@link PassiveSpoofScorer}.
 *
 * ```ts
 * const scorer = customPassiveScorer(async (canvas) => myModel.predictReal(canvas));
 * ```
 */
export function customPassiveScorer(
  score: (frame: HTMLCanvasElement) => number | Promise<number>,
  onClose: () => void = () => {}
): PassiveSpoofScorer {
  return { isAvailable: true, scoreRealProbability: score, close: onClose };
}
