# Vitalis — Android Liveness Detection Library

**Feature document for Claude Code build session**
**Target stack:** Android, Kotlin core with Java interop, minSdk TBD (recommend 24+)

---

## 1. Library name

**Vitalis** (from Latin *vitalis*, "of life"). Package suggestion: `com.<org>.vitalis`.

Rationale: short, unclaimed on Maven Central as of this writing (verify before publishing), doesn't collide with existing Android libraries, and reads clearly as "liveness" without spelling it out. Module names below use the `vitalis-` prefix.

---

## 2. Problem statement

Detect, at capture time, that:

1. The image came from the **front-facing camera** only.
2. A **single human face** is present and reasonably framed.
3. The subject is a **live person in front of the camera**, not a photo, screen replay, mask, or printed image of a face.

Requirement 3 is the actual hard problem. Requirements 1 and 2 are enforcement/detection; requirement 3 is anti-spoofing, and it is where most of the engineering budget should go.

### Non-goals (explicitly out of scope for v1)

- Face **matching/identity verification** against a stored reference photo (this is liveness only, not "is this the same person").
- Defending against **3D mask attacks** or professionally produced deepfake video injection into the camera pipeline (root/emulator camera spoofing). Flag as a known gap, not solved here.
- Cross-platform (iOS) support.

---

## 3. Threat model

| Attack | In scope v1? | Notes |
|---|---|---|
| Printed photo held up to camera | Yes | Primary target per requirement 3 |
| Phone/tablet screen replay (photo or video on another screen) | Yes | Moiré pattern / screen-reflection detection needed |
| Video replay of a real person | Partial | Active challenge-response catches most of this |
| 3D silicone/paper mask | No (v1) | Needs depth sensing or specialized model, call out as v2 |
| Camera feed injection (rooted device, virtual camera app) | No (v1) | Needs Play Integrity / SafetyNet-style attestation, separate workstream |

**Open question for you:** does this library need to defend against rooted/emulated devices (fake camera apps), or is that handled elsewhere in your fraud stack (e.g. Play Integrity API at the app layer)? This changes scope materially. [Flagging, not assuming an answer.]

---

## 4. Functional requirements (expanded)

Derived from your three, made concrete enough to build against:

- **F1.** Camera must be locked to `LENS_FACING_FRONT`. No runtime camera switch UI, no fallback to back camera.
- **F2.** Exactly one face must be present. Zero faces → reject with reason `NO_FACE`. More than one face → reject with reason `MULTIPLE_FACES`.
- **F3.** Face must occupy a minimum bounding-box ratio of the frame (avoid tiny/far faces) and be roughly frontal (yaw/pitch within threshold) — reject with `FACE_TOO_SMALL` / `FACE_NOT_FRONTAL`.
- **F4.** Liveness check must pass before capture is accepted. Two-tier approach:
  - **Passive tier (always on):** frame-level spoof scoring — texture/frequency analysis to catch print and screen-replay artifacts.
  - **Active tier (challenge-response):** prompt the user for a randomized micro-action (blink, slight head turn, smile) and verify it occurred within a time window. This is the stronger and simpler-to-implement-correctly signal — recommend building this first, passive model second.
- **F5.** On liveness failure, surface a specific, non-generic reason to the caller (see §7 result model) so the host app can guide the user ("move closer," "improve lighting," "blink when prompted").
- **F6.** No captured frame should be retained beyond what's needed for the current session unless the host app explicitly persists it — liveness library should not silently write images to disk.

---

## 5. Proposed architecture

Multi-module Android library, so consumers can depend only on what they need:

```
vitalis-core        → public API surface: LivenessDetector interface, LivenessResult, LivenessConfig
vitalis-camera       → CameraX wrapper, enforces front lens, exposes frame stream
vitalis-face         → Face detection + landmarks (ML Kit Face Detection wrapper)
vitalis-antispoof    → Challenge-response engine (blink/turn/smile) + passive frame-scoring model
vitalis-ui           → Optional: View/Compose overlay (face guide oval, prompts, capture button)
```

Consumers can pull in `vitalis-core` + `vitalis-camera` + `vitalis-face` + `vitalis-antispoof` and build their own UI, or add `vitalis-ui` for a drop-in screen.

### Suggested tech choices

- **Camera:** CameraX (`androidx.camera`) — handles lifecycle, easier front-lens lock-in than raw Camera2.
- **Face detection:** ML Kit Face Detection (on-device, free, includes landmarks + classification for eyes-open probability, which the blink challenge needs directly). **Dependency risk:** requires Google Play Services — confirm this is acceptable for your device fleet.
- **Passive anti-spoof model:** TensorFlow Lite, small MobileNet-based binary classifier (real vs. spoof), quantized for on-device inference. This needs a labeled training/eval dataset (real faces vs. printed photo vs. screen replay) — **you likely don't have this yet**; either source an open dataset (e.g. CelebA-Spoof, NUAA) for a v1 baseline model or budget for internal data collection. Do not ship this tier without an eval number you can quote to a client.

---

## 6. Public API sketch

Java interop matters here since the host app is Kotlin **and** Java. Avoid `suspend fun` on the public surface; use listener/callback style.

```kotlin
// vitalis-core

interface LivenessDetector {
    fun start(config: LivenessConfig, listener: LivenessListener)
    fun stop()
}

data class LivenessConfig(
    val minFaceRatio: Float = 0.25f,
    val maxYawDegrees: Float = 15f,
    val challengeTypes: Set<ChallengeType> = setOf(ChallengeType.BLINK),
    val challengeTimeoutMs: Long = 5000L,
    val enablePassiveModel: Boolean = true
)

enum class ChallengeType { BLINK, HEAD_TURN_LEFT, HEAD_TURN_RIGHT, SMILE }

interface LivenessListener {
    fun onStateChanged(state: LivenessState)
    fun onResult(result: LivenessResult)
    fun onError(error: LivenessError)
}

sealed class LivenessState {
    object SearchingFace : LivenessState()
    object FaceFound : LivenessState()
    data class AwaitingChallenge(val type: ChallengeType) : LivenessState()
    object Verifying : LivenessState()
}

sealed class LivenessResult {
    data class Success(val capturedFrame: ByteArray, val confidence: Float) : LivenessResult()
    data class Failure(val reason: FailureReason) : LivenessResult()
}

enum class FailureReason {
    NO_FACE, MULTIPLE_FACES, FACE_TOO_SMALL, FACE_NOT_FRONTAL,
    CHALLENGE_TIMEOUT, CHALLENGE_FAILED, SPOOF_SUSPECTED, CAMERA_ERROR
}
```

Java callers use `LivenessListener` as a standard interface — no coroutine bridging needed. Provide `@JvmStatic` factory methods on a `Vitalis` object for construction.

---

## 7. State machine

```
Idle
 → SearchingFace          (camera live, no valid face yet)
 → FaceFound               (single frontal face, size OK)
 → AwaitingChallenge(type) (random challenge issued, timer running)
 → Verifying               (challenge signal detected, running passive model + final checks)
 → Result: Success | Failure(reason)
```

Any face-loss event during `AwaitingChallenge` or `Verifying` resets to `SearchingFace`, not straight to failure — avoid punishing users for a momentary frame drop, but do cap total session time and fail out after N resets.

---

## 8. Edge cases to design for explicitly

- Multiple faces in frame (e.g. someone walks behind the user).
- Face partially out of frame / too close.
- Low light — passive spoof model accuracy degrades in poor lighting; decide whether to gate on a minimum brightness check before even attempting liveness.
- Glasses, masks (medical), facial hair — should not by themselves fail liveness.
- Device with no front camera (rare, mostly tablets) — must fail gracefully with a clear error, not crash.
- Camera permission denied or revoked mid-session.
- Orientation changes mid-capture.

---

## 9. Testing strategy

- **Unit tests:** state machine transitions, config validation, result-reason mapping — no camera needed.
- **Instrumented tests:** run against a fixed set of recorded assets (real face video, printed-photo-of-face video, screen-replay video) so spoof detection accuracy is a number you can track over builds, not a vibe. Recommend building this asset set before writing the passive model, not after.
- **Manual QA matrix:** device tier (low/mid/high), lighting (bright/dim/backlit), glasses on/off, distance (near/far).

---

## 10. Phased build plan (for the Claude Code session)

1. `vitalis-core` — data classes, interfaces, state machine skeleton, no real detection logic.
2. `vitalis-camera` — CameraX integration, front-lens enforcement, frame stream to a callback.
3. `vitalis-face` — ML Kit integration, face count / size / yaw checks wired into the state machine.
4. `vitalis-antispoof` (active tier only) — blink/head-turn challenge logic using ML Kit's eye-open-probability and head Euler angles. **Ship a working v1 with just this tier before touching the ML model.**
5. `vitalis-antispoof` (passive tier) — TFLite model integration, once you've sourced or trained a model and have an eval baseline.
6. `vitalis-ui` — optional overlay module.
7. Sample app exercising the library from both a Kotlin and a Java activity, to catch interop issues early.

---

## 11. Open questions I need answered before this is buildable end to end

- Is defending against rooted devices / virtual camera apps in scope, or handled by app-layer attestation separately?
- Is Google Play Services (for ML Kit) an acceptable dependency across your device fleet?
- Do you have, or can you source, a labeled dataset for the passive anti-spoof model, or should v1 ship active-challenge-only and add passive scoring later?
- Minimum supported Android API level?
- Does the host app need the captured frame returned to it (for upload/audit), or does liveness only need to gate a separate capture flow?
