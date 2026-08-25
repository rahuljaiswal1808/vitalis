import type { LivenessError } from "../core/types";

/**
 * getUserMedia wrapper that requests the **front (user-facing) camera** (F1).
 *
 * The web platform can't hard-guarantee the front lens the way Android's
 * CameraSelector can, but this requests `facingMode: "user"` and rejects a stream
 * that reports `facingMode: "environment"`. On desktops the facingMode is often
 * undefined (single webcam) — that is accepted.
 */
export class FrontCamera {
  private stream: MediaStream | null = null;

  async start(video: HTMLVideoElement): Promise<LivenessError | null> {
    if (!navigator.mediaDevices?.getUserMedia) {
      return { code: "no_camera", message: "getUserMedia is not available in this browser." };
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "user" }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
      const track = stream.getVideoTracks()[0];
      const facing = track?.getSettings().facingMode;
      if (facing === "environment") {
        stream.getTracks().forEach((t) => t.stop());
        return { code: "no_camera", message: "Only a rear camera is available; a front camera is required." };
      }
      this.stream = stream;
      video.srcObject = stream;
      video.setAttribute("playsinline", "true");
      video.muted = true;
      await video.play();
      return null;
    } catch (err) {
      return mapError(err);
    }
  }

  stop(): void {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
  }
}

function mapError(err: unknown): LivenessError {
  const name = (err as { name?: string })?.name ?? "";
  if (name === "NotAllowedError" || name === "SecurityError") {
    return { code: "camera_permission_denied", message: "Camera permission was denied.", cause: err };
  }
  if (name === "NotFoundError" || name === "OverconstrainedError" || name === "DevicesNotFoundError") {
    return { code: "no_camera", message: "No suitable front camera was found.", cause: err };
  }
  return { code: "camera_error", message: `Failed to start camera: ${String(name || err)}`, cause: err };
}
