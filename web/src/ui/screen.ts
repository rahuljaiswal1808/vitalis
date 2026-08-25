import type { LivenessConfig } from "../core/config";
import type { LivenessError, LivenessResult, LivenessState } from "../core/types";
import { createMediaPipeLivenessDetector } from "../antispoof/factory";
import type { PassiveSpoofScorer } from "../antispoof/passiveScorer";
import { drawOverlay, type OverlayStyle } from "./overlay";

export interface MountOptions {
  /** tasks-vision wasm directory (host it yourself). */
  wasmBasePath: string;
  /** face_landmarker.task URL (host it yourself). */
  modelAssetPath: string;
  config?: Partial<LivenessConfig>;
  passiveScorer?: PassiveSpoofScorer;
  overlayStyle?: OverlayStyle;
  onResult: (result: LivenessResult) => void;
  onError: (error: LivenessError) => void;
  onStateChanged?: (state: LivenessState) => void;
}

export interface MountHandle {
  stop(): void;
}

/**
 * Drop-in screen: builds a mirrored <video> preview + a guide-oval <canvas> overlay inside
 * `container`, loads MediaPipe, and runs a liveness session. Returns a handle to stop early.
 *
 * The host must have obtained (or be ready to prompt for) camera permission — the browser
 * prompts on first getUserMedia.
 */
export async function mountLiveness(
  container: HTMLElement,
  options: MountOptions
): Promise<MountHandle> {
  const video = document.createElement("video");
  video.autoplay = true;
  video.playsInline = true;
  video.muted = true;
  Object.assign(video.style, {
    position: "absolute", inset: "0", width: "100%", height: "100%",
    objectFit: "cover", transform: "scaleX(-1)", // mirror the front camera
  } as CSSStyleDeclaration);

  const canvas = document.createElement("canvas");
  Object.assign(canvas.style, {
    position: "absolute", inset: "0", width: "100%", height: "100%",
  } as CSSStyleDeclaration);

  if (getComputedStyle(container).position === "static") container.style.position = "relative";
  container.appendChild(video);
  container.appendChild(canvas);

  const sizeCanvas = () => {
    canvas.width = container.clientWidth;
    canvas.height = container.clientHeight;
    drawOverlay(canvas, { kind: "searching_face" }, options.overlayStyle);
  };
  sizeCanvas();
  const onResize = () => sizeCanvas();
  window.addEventListener("resize", onResize);

  const detector = await createMediaPipeLivenessDetector({
    video,
    wasmBasePath: options.wasmBasePath,
    modelAssetPath: options.modelAssetPath,
    passiveScorer: options.passiveScorer,
  });

  const cleanup = () => {
    window.removeEventListener("resize", onResize);
    detector.stop();
    video.srcObject = null;
    video.remove();
    canvas.remove();
  };

  await detector.start(options.config ?? {}, {
    onStateChanged(state) {
      drawOverlay(canvas, state, options.overlayStyle);
      options.onStateChanged?.(state);
    },
    onResult(result) {
      cleanup();
      options.onResult(result);
    },
    onError(error) {
      cleanup();
      options.onError(error);
    },
  });

  return { stop: cleanup };
}
