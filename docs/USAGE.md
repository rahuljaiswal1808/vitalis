# Vitalis — Integration & Usage Guide

A complete guide to adding Vitalis liveness detection to an Android app. For the
high-level overview and module map, see the [root README](../README.md).

---

## 1. Requirements

| Item | Value |
|---|---|
| Min SDK | 24 (Android 7.0) |
| Compile / target SDK | 35 |
| Language | Kotlin or Java (both supported on the public API) |
| Camera | Front-facing camera required |
| Google Play Services | Required (ML Kit Face Detection) |
| Build access | Google's Maven repo (`dl.google.com`) must be reachable to resolve CameraX / ML Kit |

---

## 2. Add the dependency

Vitalis is a multi-module library. Depend on **`vitalis-antispoof`** — it re-exports
`vitalis-core`, `vitalis-camera`, and `vitalis-face` — and add **`vitalis-ui`** if you
want the drop-in Compose screen.

```kotlin
dependencies {
    implementation(project(":vitalis-antispoof")) // core + camera + face + orchestrator
    implementation(project(":vitalis-ui"))         // optional: drop-in screen + overlay
}
```

> Vitalis is not yet published to Maven Central. Consume it as included Gradle modules,
> or publish the AARs to your internal Maven repository and depend on the coordinates.

---

## 3. Declare and request the camera permission

The library declares `android.permission.CAMERA` in its manifest (merged automatically),
but **you** must request it at runtime before starting a session. Vitalis never requests
permissions on your behalf.

```kotlin
private val cameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted -> if (granted) startLiveness() else showRationale() }

if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) {
    startLiveness()
} else {
    cameraPermission.launch(Manifest.permission.CAMERA)
}
```

If permission is missing or revoked mid-session, you receive
`LivenessError(CAMERA_PERMISSION_DENIED, …)` (or `CAMERA_ERROR`) — see §7.

---

## 4. Run a session

### 4a. Drop-in Compose screen (fastest)

```kotlin
setContent {
    VitalisLivenessScreen(
        config = LivenessConfig(),
        onResult = { result -> /* handle Success / Failure */ },
        onError = { error -> /* handle system error */ }
    )
}
```

The screen renders the front-camera preview + the guide-oval overlay and manages the
detector for its whole composition (started on enter, stopped on exit).

### 4b. Manual wiring (own UI, Kotlin)

```kotlin
val previewView = PreviewView(context)   // add this to your layout
val detector = Vitalis.create(context, lifecycleOwner, previewView)

detector.start(
    LivenessConfig(
        challengeTypes = setOf(ChallengeType.BLINK, ChallengeType.HEAD_TURN_LEFT),
        requiredChallengeCount = 2
    ),
    object : LivenessListener {
        override fun onStateChanged(state: LivenessState) {
            promptLabel.text = LivenessPrompts.forState(state)
        }
        override fun onResult(result: LivenessResult) = when (result) {
            is LivenessResult.Success -> onPass(result.capturedFrame, result.confidence)
            is LivenessResult.Failure -> onFail(result.reason)
        }
        override fun onError(error: LivenessError) = onSystemError(error)
    }
)

// When the user cancels / the screen is destroyed:
override fun onDestroy() { super.onDestroy(); detector.stop() }
```

### 4c. Manual wiring (Java)

```java
LivenessConfig config = LivenessConfig.builder()
        .challengeTypes(Collections.singleton(ChallengeType.BLINK))
        .minFaceRatio(0.25f)
        .returnCapturedFrame(true)
        .build();

LivenessDetector detector = Vitalis.create(this, this, previewView);
detector.start(config, this);   // 'this' implements LivenessListener

// Kotlin objects are reached via INSTANCE from Java:
statusText.setText(LivenessPrompts.INSTANCE.forState(state));
```

No coroutines are involved anywhere on the public surface — `LivenessListener` is a plain
callback interface, and all callbacks arrive on the **main thread**.

---

## 5. Configuration reference (`LivenessConfig`)

All fields have defaults; override only what you need. Construct via the Kotlin data-class
constructor or `LivenessConfig.builder()` (Java). `validate()` runs automatically on `start`
and throws `IllegalArgumentException` with a specific message on bad input.

| Field | Default | Meaning |
|---|---|---|
| `minFaceRatio` | `0.25` | Min face box size as a fraction of the smaller frame dimension. Larger ⇒ user must be closer. (`FACE_TOO_SMALL`) |
| `maxYawDegrees` | `15` | Max left/right head turn to still count as "frontal". (`FACE_NOT_FRONTAL`) |
| `maxPitchDegrees` | `15` | Max up/down nod to still count as "frontal". |
| `challengeTypes` | `{BLINK}` | Pool of challenges; each required challenge is chosen at random from it. |
| `requiredChallengeCount` | `1` | How many challenges must pass before verifying. |
| `challengeTimeoutMs` | `5000` | Per-challenge budget. (`CHALLENGE_TIMEOUT`) |
| `sessionTimeoutMs` | `30000` | Total session budget. Must be ≥ `challengeTimeoutMs`. |
| `maxFaceLossResets` | `3` | Face-loss events tolerated during a challenge before failing. (`CHALLENGE_FAILED`) |
| `enablePassiveModel` | `true` | Whether to run the passive TFLite tier *if a scorer is available*. |
| `minBrightness` | `0.15` | Min average frame luma to attempt liveness; `≤0` disables the gate. (`LOW_LIGHT`) |
| `eyeClosedThreshold` | `0.35` | Eye-open prob below which an eye is "closed" (blink). Must be `<` `eyeOpenThreshold`. |
| `eyeOpenThreshold` | `0.65` | Eye-open prob above which an eye is "open" (blink). |
| `headTurnYawDegrees` | `22` | Yaw magnitude that satisfies a HEAD_TURN. Must exceed `maxYawDegrees`. |
| `smileThreshold` | `0.7` | Smile prob that satisfies SMILE. |
| `neutralSmileThreshold` | `0.3` | Smile prob the face must first drop below to arm SMILE. Must be `<` `smileThreshold`. |
| `returnCapturedFrame` | `true` | If true, `Success.capturedFrame` holds the verified frame as JPEG bytes; else empty. |
| `passiveRealProbThreshold` | `0.5` | Min "real" probability from the passive model to accept; below ⇒ `SPOOF_SUSPECTED`. |

### Tuning notes

- **Stronger liveness:** raise `requiredChallengeCount` to 2–3 and include multiple
  `challengeTypes` so the action sequence is unpredictable.
- **Faster/looser (kiosk, good lighting):** single BLINK, larger `challengeTimeoutMs`.
- **Challenges each require a *transition*** (eyes open→closed→open; neutral→smile;
  frontal→turned), so a static photo held to the camera cannot satisfy them.

---

## 6. Lifecycle & state machine

```
Idle → SearchingFace → FaceFound → AwaitingChallenge(type) → Verifying → Success | Failure(reason)
```

- A face lost during a challenge resets to `SearchingFace` (not an instant failure), up to
  `maxFaceLossResets`; then `CHALLENGE_FAILED`.
- Drive your UI text from `onStateChanged` via `LivenessPrompts.forState(state)`.
- Call `detector.stop()` when the user backs out or the host is destroyed. A detector is
  **single-session** — create a new one per attempt (or just re-enter `VitalisLivenessScreen`).

---

## 7. Handling results

### `LivenessResult.Success`
- `capturedFrame: ByteArray` — JPEG of the verified frame (empty if `returnCapturedFrame=false`).
- `confidence: Float` — combined active (and passive, if enabled) confidence in `[0,1]`.

### `LivenessResult.Failure(reason)` — map each reason to guidance (F5)

| `FailureReason` | Suggested user message |
|---|---|
| `NO_FACE` | "No face detected — face the camera" |
| `MULTIPLE_FACES` | "Only one person should be in frame" |
| `FACE_TOO_SMALL` | "Move a little closer" |
| `FACE_NOT_FRONTAL` | "Look straight at the camera" |
| `LOW_LIGHT` | "Find better lighting" |
| `CHALLENGE_TIMEOUT` | "Timed out — let's try again" |
| `CHALLENGE_FAILED` | "We couldn't complete the check — try again" |
| `SPOOF_SUSPECTED` | "Verification failed" |
| `CAMERA_ERROR` | "Camera error" |

`LivenessPrompts.forFailure(reason)` returns these strings; override for localization.

### `LivenessError` — system/environment problems (not a failed subject)

| `LivenessError.Code` | When |
|---|---|
| `NO_FRONT_CAMERA` | Device has no front camera. |
| `CAMERA_PERMISSION_DENIED` | Permission missing/revoked. |
| `CAMERA_ERROR` | CameraX/device failure. |
| `PASSIVE_MODEL_UNAVAILABLE` | Passive model requested but not loadable. |
| `INTERNAL` | Unexpected failure. |

---

## 8. Enabling the passive anti-spoof tier

Vitalis ships **no** model — the active challenge tier runs on its own. To add passive
frame-level spoof scoring, supply your own validated TFLite model:

1. Train/source a binary real-vs-spoof classifier (e.g. MobileNet on CelebA-Spoof / NUAA).
   Model contract: input `[1,128,128,3]` float32 RGB `[0,1]`, output `[1,1]` sigmoid = P(real).
2. Put it at `src/main/assets/vitalis_spoof.tflite`.
3. Wire it up:

```kotlin
val scorer = TfliteSpoofScorer.fromAsset(context)   // isAvailable == false if asset absent
val detector = Vitalis.createWithPassiveScorer(context, lifecycleOwner, scorer, previewView)
```

If the model is absent or fails to load, `isAvailable` is `false` and Vitalis transparently
runs active-only — it never crashes the session. **Do not enable this in production without
an eval number you can quote.**

---

## 9. Privacy (F6)

The library never writes frames to disk. The only frame that leaves the pipeline is the
verified frame returned in-memory via `Success.capturedFrame`, and only when
`returnCapturedFrame = true`. If your flow doesn't need the image, set it to `false`.

---

## 10. ProGuard / R8

Each module ships consumer ProGuard rules that keep its public API, so no host-side rules
are required for Vitalis itself. If you enable the passive tier, the TFLite keep-rule is
already bundled in `vitalis-antispoof`.

---

## 11. Testing your integration

- `./gradlew :vitalis-core:test` runs the pure-JVM state-machine + config tests (no device).
- Per feature-spec §9, add an instrumented suite that replays recorded **real /
  printed-photo / screen-replay** clips so spoof accuracy is a tracked number over builds.
- Manual QA matrix: device tier (low/mid/high), lighting (bright/dim/backlit),
  glasses on/off, distance (near/far).

---

## 12. Known limitations

- No passive model bundled (active-only until you add one).
- No defense against 3D masks or camera-feed injection on rooted/emulated devices — pair
  Vitalis with app-layer attestation (e.g. Play Integrity) for that threat class.
- Liveness only — no face matching / identity verification.
