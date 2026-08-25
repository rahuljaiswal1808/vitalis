import type { LivenessState } from "../core/types";
import { LivenessPrompts } from "./prompts";

export interface OverlayStyle {
  ovalColor?: string;
  scrimColor?: string;
  textColor?: string;
  font?: string;
}

/**
 * Draw the guide-oval + prompt overlay onto a 2D canvas sized to match the preview.
 * Purely presentational — call it whenever the state changes (or each frame).
 */
export function drawOverlay(
  canvas: HTMLCanvasElement,
  state: LivenessState,
  style: OverlayStyle = {}
): void {
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const w = canvas.width;
  const h = canvas.height;
  ctx.clearRect(0, 0, w, h);

  const ovalW = w * 0.7;
  const ovalH = ovalW * 1.3;
  const cx = w / 2;
  const cy = h / 2.4 + ovalH / 2;

  // Scrim with an oval "window" punched out via even-odd fill.
  ctx.save();
  ctx.beginPath();
  ctx.rect(0, 0, w, h);
  ctx.ellipse(cx, cy, ovalW / 2, ovalH / 2, 0, 0, Math.PI * 2);
  ctx.fillStyle = style.scrimColor ?? "rgba(0,0,0,0.55)";
  ctx.fill("evenodd");
  ctx.restore();

  // Oval outline.
  ctx.beginPath();
  ctx.ellipse(cx, cy, ovalW / 2, ovalH / 2, 0, 0, Math.PI * 2);
  ctx.strokeStyle = style.ovalColor ?? "#ffffff";
  ctx.lineWidth = 4;
  ctx.stroke();

  // Prompt text.
  ctx.fillStyle = style.textColor ?? "#ffffff";
  ctx.font = style.font ?? "600 20px system-ui, sans-serif";
  ctx.textAlign = "center";
  ctx.fillText(LivenessPrompts.forState(state), cx, h - 32);
}
