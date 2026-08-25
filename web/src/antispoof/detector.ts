import { FrameCanvas, FrontCamera } from "../camera";
import { LivenessEngine } from "../core/engine";
import { resolveConfig, type LivenessConfig } from "../core/config";
import type {
  EngineEvent,
  FaceSignals,
  LivenessListener,
  LivenessResult,
} from "../core/types";
import type { FaceDetectorAdapter } from "../face/adapter";
import { NoPassiveScorer, type PassiveSpoofScorer } from "./passiveScorer";

export interface DetectorOptions {
  /** The <video> element that shows the front-camera preview. */
  video: HTMLVideoElement;
  /** Face-detection backend (e.g. from createMediaPipeFaceDetector). */
  faceDetector: FaceDetectorAdapter;
  /** Optional passive spoof model; omit for active-only. */
  passiveScorer?: PassiveSpoofScorer;
}

/**
 * Browser orchestrator (mirrors Android `VitalisLivenessDetector`): front camera →
 * face adapter → pure engine → optional passive scorer. Single-session; create one,
 * {@link start}, then {@link stop}.
 */
export class VitalisLivenessDetector {
  private readonly video: HTMLVideoElement;
  private readonly faceDetector: FaceDetectorAdapter;
  private readonly passiveScorer: PassiveSpoofScorer;

  private readonly camera = new FrontCamera();
  private readonly frame = new FrameCanvas();
  private engine: LivenessEngine | null = null;
  private listener: LivenessListener | null = null;
  private config: LivenessConfig = resolveConfig();

  private rafId = 0;
  private tickTimer: ReturnType<typeof setInterval> | null = null;
  private finished = false;
  private busy = false; // guards the async verify step

  constructor(options: DetectorOptions) {
    this.video = options.video;
    this.faceDetector = options.faceDetector;
    this.passiveScorer = options.passiveScorer ?? NoPassiveScorer;
  }

  async start(config: Partial<LivenessConfig>, listener: LivenessListener): Promise<void> {
    this.config = resolveConfig(config);
    this.listener = listener;
    this.finished = false;

    const engine = new LivenessEngine(this.config);
    this.engine = engine;

    const error = await this.camera.start(this.video);
    if (error) {
      this.finishWithError(error);
      return;
    }

    this.dispatch(engine.start(now()));
    this.tickTimer = setInterval(() => {
      if (this.engine && !this.finished) this.dispatch(this.engine.onTick(now()));
    }, 500);

    this.loop();
  }

  stop(): void {
    this.finished = true;
    this.teardown();
  }

  private loop = (): void => {
    if (this.finished) return;
    this.rafId = requestAnimationFrame(this.loop);
    if (this.busy || !this.engine) return;
    if (this.video.readyState < 2 /* HAVE_CURRENT_DATA */) return;

    const nowMs = now();
    let signals: FaceSignals;
    try {
      const metrics = this.faceDetector.detect(this.video, nowMs);
      const brightness = this.frame.brightness(this.video);
      signals = { ...metrics, brightness };
    } catch (err) {
      this.finishWithError({ code: "internal", message: `Detection failed: ${String(err)}`, cause: err });
      return;
    }
    void this.handleEvents(this.engine.onFrame(signals));
  };

  private async handleEvents(events: EngineEvent[]): Promise<void> {
    for (const event of events) {
      if (this.finished) return;
      switch (event.type) {
        case "state_changed":
          this.listener?.onStateChanged(event.state);
          break;
        case "rejected":
          this.finishWithResult({ ok: false, reason: event.reason });
          return;
        case "verified": {
          this.busy = true;
          const result = await this.verify(event.confidence);
          this.finishWithResult(result);
          return;
        }
      }
    }
  }

  private async verify(activeConfidence: number): Promise<LivenessResult> {
    // Capture the verified frame first (F6: in-memory only, host decides persistence).
    this.frame.capture(this.video);
    const capturedFrame = this.config.returnCapturedFrame ? await this.frame.toJpeg() : null;

    if (this.config.enablePassiveModel && this.passiveScorer.isAvailable) {
      const canvas = this.frame.capture(this.video);
      const realProb = await this.passiveScorer.scoreRealProbability(canvas);
      if (realProb < this.config.passiveRealProbThreshold) {
        return { ok: false, reason: "spoof_suspected" };
      }
      return { ok: true, capturedFrame, confidence: clamp(activeConfidence * realProb, 0, 1) };
    }
    return { ok: true, capturedFrame, confidence: activeConfidence };
  }

  private dispatch(events: EngineEvent[]): void {
    for (const event of events) {
      switch (event.type) {
        case "state_changed":
          this.listener?.onStateChanged(event.state);
          break;
        case "rejected":
          this.finishWithResult({ ok: false, reason: event.reason });
          return;
        case "verified":
          this.finishWithResult({ ok: true, capturedFrame: null, confidence: event.confidence });
          return;
      }
    }
  }

  private finishWithResult(result: LivenessResult): void {
    if (this.finished) return;
    this.finished = true;
    const l = this.listener;
    this.teardown();
    l?.onResult(result);
  }

  private finishWithError(error: Parameters<LivenessListener["onError"]>[0]): void {
    if (this.finished) return;
    this.finished = true;
    const l = this.listener;
    this.teardown();
    l?.onError(error);
  }

  private teardown(): void {
    if (this.rafId) cancelAnimationFrame(this.rafId);
    this.rafId = 0;
    if (this.tickTimer) clearInterval(this.tickTimer);
    this.tickTimer = null;
    this.camera.stop();
    this.engine = null;
    this.busy = false;
  }
}

function now(): number {
  return performance.now();
}
function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}
