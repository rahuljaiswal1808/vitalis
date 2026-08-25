import { UNKNOWN } from "../core/types";

/**
 * A reusable offscreen canvas for per-frame work (brightness + JPEG capture).
 * One instance per session avoids allocating a canvas on every frame.
 */
export class FrameCanvas {
  private readonly canvas: HTMLCanvasElement;
  private readonly ctx: CanvasRenderingContext2D;
  /** Small canvas used only for the cheap brightness sample. */
  private readonly lumaCanvas: HTMLCanvasElement;
  private readonly lumaCtx: CanvasRenderingContext2D;

  constructor() {
    this.canvas = document.createElement("canvas");
    this.ctx = this.canvas.getContext("2d", { willReadFrequently: false })!;
    this.lumaCanvas = document.createElement("canvas");
    this.lumaCanvas.width = 32;
    this.lumaCanvas.height = 32;
    this.lumaCtx = this.lumaCanvas.getContext("2d", { willReadFrequently: true })!;
  }

  /** Draw the current video frame into the full-size canvas and return it. */
  capture(video: HTMLVideoElement): HTMLCanvasElement {
    const w = video.videoWidth;
    const h = video.videoHeight;
    if (w === 0 || h === 0) return this.canvas;
    if (this.canvas.width !== w) this.canvas.width = w;
    if (this.canvas.height !== h) this.canvas.height = h;
    this.ctx.drawImage(video, 0, 0, w, h);
    return this.canvas;
  }

  /** Average luma of the current video frame in [0,1], or UNKNOWN. Cheap (32x32 sample). */
  brightness(video: HTMLVideoElement): number {
    if (video.videoWidth === 0 || video.videoHeight === 0) return UNKNOWN;
    this.lumaCtx.drawImage(video, 0, 0, 32, 32);
    const { data } = this.lumaCtx.getImageData(0, 0, 32, 32);
    let sum = 0;
    for (let i = 0; i < data.length; i += 4) {
      // Rec. 601 luma.
      sum += 0.299 * data[i]! + 0.587 * data[i + 1]! + 0.114 * data[i + 2]!;
    }
    return sum / (32 * 32) / 255;
  }

  /** Encode the last captured full-size canvas as a JPEG Blob (F6: never written to disk). */
  toJpeg(quality = 0.9): Promise<Blob | null> {
    return new Promise((resolve) => this.canvas.toBlob((b) => resolve(b), "image/jpeg", quality));
  }
}
