/**
 * Vitalis — browser liveness detection.
 *
 * Entry points:
 *  - mountLiveness(container, opts)        → drop-in screen (easiest)
 *  - createMediaPipeLivenessDetector(opts) → detector with MediaPipe wired up
 *  - createLivenessDetector(opts)          → bring your own face adapter
 */
export * from "./core";
export * from "./camera";
export * from "./face";
export * from "./antispoof";
export * from "./ui";
