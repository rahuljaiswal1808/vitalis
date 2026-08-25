# Vitalis — Android Liveness Detection Library

[![CI](https://github.com/rahuljaiswal1808/vitalis/actions/workflows/ci.yml/badge.svg)](https://github.com/rahuljaiswal1808/vitalis/actions/workflows/ci.yml)

Vitalis detects, at capture time, that a **live person** is in front of the **front-facing
camera** — not a printed photo, a screen replay, or a static image. It is a *liveness*
library only; it does **not** do face matching / identity verification.

> Built from the feature specification in `docs/`. Section references below (e.g. §4/F4)
> point back to that document.
>
> **New to the library? Start with the [Integration & Usage Guide](docs/USAGE.md).**

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `vitalis-core` | Pure Kotlin/JVM | Public API (`LivenessDetector`, `LivenessConfig`, `LivenessResult`, …) and the **entire liveness state machine + challenge logic**. No Android/camera/ML deps — fully unit-tested. |
| `vitalis-camera` | Android lib | CameraX wrapper **hard-locked to the front lens** (F1); frame stream + luma/JPEG utils. |
| `vitalis-face` | Android lib | ML Kit Face Detection → neutral `FaceSignals` (count, size, yaw/pitch, eye-open, smile). |
| `vitalis-antispoof` | Android lib | Orchestrator (`VitalisLivenessDetector`) wiring camera→face→engine, the `Vitalis` factory, and the passive TFLite spoof tier (**scaffold, no model shipped**). |
| `vitalis-ui` | Android lib (Compose) | Optional drop-in `VitalisLivenessScreen` + `LivenessOverlay` (guide oval, prompts). |
| `sample` | App | Exercises the library from **both a Kotlin and a Java** activity (interop, §10.7). |

Depend on `vitalis-antispoof` (it re-exports core/camera/face) to build your own UI, or add
`vitalis-ui` for a ready-made screen.

### Web / JavaScript

A browser port lives in [`web/`](web/) (npm package `vitalis-liveness`): a 1:1 TypeScript
port of the core state machine plus adapters for `getUserMedia` (front-lens), MediaPipe
face detection, and a drop-in overlay. Same config, states, and failure reasons as Android.
See [web/README.md](web/README.md).

## Architecture at a glance

```
CameraX (front only) ──frames──▶ ML Kit face detection ──FaceSignals──▶ LivenessEngine (pure)
   vitalis-camera                    vitalis-face                          vitalis-core
                                                                              │ EngineEvent
                                                                              ▼
                                              VitalisLivenessDetector ──▶ (optional) PassiveSpoofScorer
                                                  vitalis-antispoof              TFLite (your model)
                                                                              │
                                                                              ▼  LivenessResult
```

The engine is deliberately byte-free and Android-free: `vitalis-face` translates each frame
into a numeric `FaceSignals`, and the pure-Kotlin engine does all geometric gating (F2/F3) and
active-tier challenge detection (F4). This is why the whole state machine is testable on a plain
JVM with no device (`./gradlew :vitalis-core:test`).

## Quick start (Kotlin)

```kotlin
val detector = Vitalis.create(context, lifecycleOwner, previewView) // active-tier only
detector.start(
    LivenessConfig(
        challengeTypes = setOf(ChallengeType.BLINK, ChallengeType.SMILE),
        requiredChallengeCount = 2
    ),
    object : LivenessListener {
        override fun onStateChanged(state: LivenessState) { /* drive UI prompts */ }
        override fun onResult(result: LivenessResult) {
            when (result) {
                is LivenessResult.Success -> { /* result.capturedFrame, result.confidence */ }
                is LivenessResult.Failure -> { /* result.reason */ }
            }
        }
        override fun onError(error: LivenessError) { /* NO_FRONT_CAMERA, CAMERA_ERROR, … */ }
    }
)
```

## Quick start (Java)

```java
LivenessConfig config = LivenessConfig.builder()
        .challengeTypes(Collections.singleton(ChallengeType.BLINK))
        .minFaceRatio(0.25f)
        .build();
LivenessDetector detector = Vitalis.create(this, this, previewView);
detector.start(config, this); // 'this' implements LivenessListener — no coroutines needed
```

Or just drop in the Compose screen:

```kotlin
VitalisLivenessScreen(config = LivenessConfig(), onResult = { … }, onError = { … })
```

## What ships in v1

- **F1** Front-lens lock (no switch UI, no back-camera fallback).
- **F2** Exactly one face (`NO_FACE` / `MULTIPLE_FACES`).
- **F3** Min face size + frontal pose (`FACE_TOO_SMALL` / `FACE_NOT_FRONTAL`).
- **F4 (active tier)** Randomized challenge-response: blink / head-turn / smile, each using
  a real *transition* (e.g. open→closed→open eyes) so a static photo can't satisfy it.
- **F5** Specific failure reasons for host guidance.
- **F6** No frame is written to disk by the library; the verified frame is returned in-memory
  only when `returnCapturedFrame` is set.

## What does NOT ship (known gaps)

- **Passive anti-spoof model (F4 passive tier).** The integration point exists
  (`PassiveSpoofScorer` + `TfliteSpoofScorer.fromAsset`), but **no validated model is bundled**
  — shipping an un-evaluated model gives false confidence (§5). Supply your own model
  (trained/sourced on e.g. CelebA-Spoof / NUAA) and quote an eval number before enabling it.
  Until then Vitalis runs **active-challenge-only** and degrades gracefully.
- **3D mask attacks** and **camera-feed injection** on rooted/emulated devices — out of scope
  for v1 (§3). Pair Vitalis with app-layer attestation (e.g. Play Integrity) for that threat.
- **Face matching / identity** — this is liveness only.

## Decisions taken on the spec's open questions (§11)

These were flagged as needing answers; v1 proceeds with defensible defaults you can revisit:

1. **Rooted/virtual-camera defense** — treated as out of scope; assumed handled by app-layer
   attestation. Documented as a gap, not silently ignored.
2. **Google Play Services / ML Kit dependency** — accepted as the v1 face-detection backend.
3. **Passive dataset** — none assumed; v1 is active-challenge-only with the passive tier
   scaffolded for later.
4. **min SDK** — 24 (per the doc's recommendation).
5. **Captured frame returned?** — yes by default (`returnCapturedFrame = true`), toggleable.

## Building

Requires the Android SDK and access to Google's Maven repository (`dl.google.com`).

```bash
./gradlew :vitalis-core:test        # pure-JVM unit tests — no SDK needed
./gradlew assemble                  # all library AARs + sample APK
./gradlew :sample:installDebug      # run the sample on a device
```

Enabling the passive tier once you have a model:

```kotlin
// place your model at sample/src/main/assets/vitalis_spoof.tflite
val scorer = TfliteSpoofScorer.fromAsset(context)           // isAvailable == false if absent
val detector = Vitalis.createWithPassiveScorer(context, owner, scorer, previewView)
```

## Testing

`vitalis-core` carries JVM unit tests for config validation and every state-machine
transition (happy path, multi-face, too-small, timeouts, static-photo rejection, head-turn,
face-loss reset/cap, low-light). Per §9, add an instrumented suite that replays recorded
real / printed-photo / screen-replay assets so spoof accuracy is a tracked number.

## License

MIT — see `LICENSE`.
