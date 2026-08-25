import type { ChallengeType, FailureReason, LivenessState } from "../core/types";

/** User-facing guidance strings (F5). Override for localization. */
export const LivenessPrompts = {
  forState(state: LivenessState): string {
    switch (state.kind) {
      case "searching_face":
        return "Position your face in the oval";
      case "face_found":
        return "Hold still…";
      case "awaiting_challenge":
        return LivenessPrompts.forChallenge(state.challenge);
      case "verifying":
        return "Verifying…";
    }
  },
  forChallenge(type: ChallengeType): string {
    switch (type) {
      case "blink":
        return "Blink slowly";
      case "head_turn_left":
        return "Turn your head to the left";
      case "head_turn_right":
        return "Turn your head to the right";
      case "smile":
        return "Smile";
    }
  },
  forFailure(reason: FailureReason): string {
    switch (reason) {
      case "no_face":
        return "No face detected — face the camera";
      case "multiple_faces":
        return "Only one person should be in frame";
      case "face_too_small":
        return "Move a little closer";
      case "face_not_frontal":
        return "Look straight at the camera";
      case "low_light":
        return "Find better lighting";
      case "challenge_timeout":
        return "Timed out — let's try again";
      case "challenge_failed":
        return "We couldn't complete the check — try again";
      case "spoof_suspected":
        return "Verification failed";
      case "camera_error":
        return "Camera error";
    }
  },
};
