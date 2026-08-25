# Vitalis (Web) — Browser Liveness Detection

A JavaScript/TypeScript port of the [Vitalis](../README.md) Android liveness library.
It confirms, at capture time, that a **live person** is in front of the **front camera** —
not a printed photo, a screen replay, or a static image. Liveness only; no face matching.

The core state machine is a **1:1 port** of the Android engine, so both platforms behave
identically and share the same config, states, and failure reasons.

## Install

```bash
npm install vitalis-liveness @mediapipe/tasks-vision
```

`@mediapipe/tasks-vision` is an optional peer dependency — needed only for the built-in
face detector. You host its WASM runtime and the `face_landmarker.task` model yourself.

Or use the standalone build with a plain `<script>` tag (MediaPipe JS bundled in):

```html
<script src="vitalis.global.js"></script> <!-- exposes window.Vitalis -->
```

## Quick start — drop-in screen

```ts
import { mountLiveness } from "vitalis-liveness";

const handle = await mountLiveness(document.getElementById("stage")!, {
  wasmBasePath: "/vendor/mediapipe/wasm",           // self-hosted tasks-vision wasm dir
  modelAssetPath: "/vendor/mediapipe/face_landmarker.task",
  config: { challengeTypes: ["blink", "smile"], requiredChallengeCount: 2 },
  onResult: (r) => {
    if (r.ok) console.log("PASS", r.confidence, r.capturedFrame);
    else console.log("FAIL", r.reason);
  },
  onError: (e) => console.error(e.code, e.message),
});
// handle.stop() to cancel early
```

`mountLiveness` builds a mirrored `<video>` preview + a guide-oval overlay inside your
container and runs the whole session. The browser prompts for camera permission on start.

## Quick start — manual wiring

```ts
import { createMediaPipeLivenessDetector, LivenessPrompts } from "vitalis-liveness";

const detector = await createMediaPipeLivenessDetector({
  video: myVideoEl,
  wasmBasePath: "/vendor/mediapipe/wasm",
  modelAssetPath: "/vendor/mediapipe/face_landmarker.task",
});

detector.start(
  { challengeTypes: ["blink"], requiredChallengeCount: 1 },
  {
    onStateChanged: (s) => (label.textContent = LivenessPrompts.forState(s)),
    onResult: (r) => (r.ok ? onPass(r) : onFail(r.reason)),
    onError: (e) => onError(e),
  }
);
// detector.stop() when the user backs out
```

## Architecture (mirrors Android)

```
getUserMedia (front) ──frames──▶ MediaPipe FaceLandmarker ──FaceSignals──▶ LivenessEngine (pure)
   camera/                          face/                                    core/
                                                                              │ EngineEvent
                                                                              ▼
                                              VitalisLivenessDetector ──▶ (optional) PassiveSpoofScorer
                                                  antispoof/                     your tfjs/onnx model
                                                                              │  LivenessResult
```

| Folder | Responsibility |
|---|---|
| `core/` | Pure TS: types, config, and the **entire state machine + challenge logic**. No DOM — unit-tested in node. Import via `vitalis-liveness/core` for a browserless bundle. |
| `camera/` | `getUserMedia` locked to the front lens (F1); brightness + JPEG capture. |
| `face/` | `FaceDetectorAdapter` interface + MediaPipe FaceLandmarker implementation. |
| `antispoof/` | Orchestrator, factory, and the passive spoof-scorer interface (bring your own model). |
| `ui/` | Prompts, canvas overlay, and the `mountLiveness` drop-in. |

## What ships / what doesn't

- **Active tier (ships):** blink / head-turn / smile challenges, each requiring a real
  *transition* (e.g. eyes open→closed→open) so a static photo can't satisfy them.
- **Passive tier (bring your own model):** `PassiveSpoofScorer` + `customPassiveScorer(fn)`
  let you plug in a tfjs / onnxruntime-web real-vs-spoof model. **No model ships** — enable it
  only with an eval number you can quote (feature spec §5). Runs active-only otherwise.
- **Not covered:** 3D masks, virtual-camera / injection on the client. The browser can't
  attest the camera pipeline — pair with server-side signals for that threat class.

## Config & results

`LivenessConfig`, `LivenessState`, `FailureReason`, and `LivenessResult` match the Android
API field-for-field — see the [root README](../README.md) and
[docs/USAGE.md](../docs/USAGE.md) for the full reference tables. Pass a partial config; the
rest fills from defaults and is validated on `start`.

Results:
- `{ ok: true, capturedFrame: Blob | null, confidence: number }`
- `{ ok: false, reason: FailureReason }`

## Security notes for the web

- **HTTPS required** — `getUserMedia` only works on secure origins (or `localhost`).
- **Self-host the MediaPipe wasm + model** and set a CSP that allows them; don't depend on a
  third-party CDN in production.
- **Client-side liveness is defense-in-depth, not proof.** A determined attacker controls the
  browser and can feed a virtual camera. For higher assurance, combine with server-side checks
  and device attestation.

## Development

```bash
npm install
npm test          # vitest — pure-core engine + config tests (17 tests)
npm run typecheck # tsc across all sources (browser code included)
npm run build     # tsup → ESM + CJS + .d.ts + standalone IIFE (window.Vitalis)
```

## Try the demo in a browser

A ready-to-run demo lives in `examples/` — a full page with challenge selection, live
status, pass/fail, and a preview of the captured frame.

```bash
cd web
npm install
npm run demo     # builds the library, then serves http://localhost:5173
```

Open **http://localhost:5173/** and click **Start liveness**. `getUserMedia` requires a
secure origin, and `localhost` counts — so the demo works over plain http locally with no
TLS setup. The MediaPipe wasm + face model load from a CDN in the demo; self-host them (and
set a CSP) for production.

`npm run serve` serves without rebuilding if `dist/` already exists.

### Hosted demo (GitHub Pages)

`.github/workflows/pages.yml` builds the bundle and publishes the demo to GitHub Pages
on every push to `main`. One-time setup by a repo admin: **Settings → Pages → Build and
deployment → Source: GitHub Actions**. After that, the demo is served at
`https://rahuljaiswal1808.github.io/vitalis/`.

The MediaPipe wasm + face model still load from a CDN in the hosted demo; GitHub Pages
serves the page over HTTPS, so `getUserMedia` works.

## License

MIT.
