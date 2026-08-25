import { describe, expect, it } from "vitest";
import { LivenessEngine, resolveConfig } from "../src/core";
import { Recorder, multiFace, noFace, validFrontal } from "./helpers";

// Deterministic RNG so challenge selection is stable across runs.
const seeded = () => 0;

function engine(partial = {}) {
  return new LivenessEngine(resolveConfig(partial), seeded);
}

function blink(start: number, e: LivenessEngine, rec: Recorder) {
  rec.feed(e.onFrame(validFrontal(start, { leftEyeOpenProbability: 0.95, rightEyeOpenProbability: 0.95 })));
  rec.feed(e.onFrame(validFrontal(start + 100, { leftEyeOpenProbability: 0.1, rightEyeOpenProbability: 0.1 })));
  rec.feed(e.onFrame(validFrontal(start + 200, { leftEyeOpenProbability: 0.95, rightEyeOpenProbability: 0.95 })));
}

describe("LivenessEngine", () => {
  it("happy path (blink) reaches verified", () => {
    const e = engine({ challengeTypes: ["blink"] });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10)));
    rec.feed(e.onFrame(validFrontal(20)));
    blink(30, e, rec);

    const kinds = rec.states().map((s) => s.kind);
    expect(kinds).toContain("searching_face");
    expect(kinds).toContain("face_found");
    expect(kinds).toContain("awaiting_challenge");
    expect(kinds).toContain("verifying");
    expect(rec.result()?.type).toBe("verified");
  });

  it("multiple faces reject immediately", () => {
    const e = engine();
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(multiFace(10)));
    expect(rec.result()).toEqual({ type: "rejected", reason: "multiple_faces" });
  });

  it("too-small face times out as face_too_small", () => {
    const e = engine({ sessionTimeoutMs: 1000, challengeTimeoutMs: 1000, minFaceRatio: 0.25 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10, { boundingBoxRatio: 0.1 })));
    rec.feed(e.onFrame(validFrontal(2000, { boundingBoxRatio: 0.1 })));
    expect(rec.result()).toEqual({ type: "rejected", reason: "face_too_small" });
  });

  it("no face times out as no_face", () => {
    const e = engine({ sessionTimeoutMs: 1000, challengeTimeoutMs: 1000 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(noFace(10)));
    rec.feed(e.onTick(2000));
    expect(rec.result()).toEqual({ type: "rejected", reason: "no_face" });
  });

  it("challenge times out when user does nothing", () => {
    const e = engine({ challengeTypes: ["blink"], challengeTimeoutMs: 500, sessionTimeoutMs: 10000 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10)));
    rec.feed(e.onFrame(validFrontal(20)));
    rec.feed(e.onTick(1000));
    expect(rec.result()).toEqual({ type: "rejected", reason: "challenge_timeout" });
  });

  it("static open eyes never count as a blink, then timeout", () => {
    const e = engine({ challengeTypes: ["blink"], challengeTimeoutMs: 500, sessionTimeoutMs: 10000 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10)));
    rec.feed(e.onFrame(validFrontal(20)));
    for (let i = 0; i < 3; i++) {
      rec.feed(e.onFrame(validFrontal(30 + i, { leftEyeOpenProbability: 0.95, rightEyeOpenProbability: 0.95 })));
    }
    rec.feed(e.onTick(1000));
    expect(rec.result()).toEqual({ type: "rejected", reason: "challenge_timeout" });
  });

  it("head-turn right passes", () => {
    const e = engine({ challengeTypes: ["head_turn_right"], headTurnYawDegrees: 22 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10)));
    rec.feed(e.onFrame(validFrontal(20)));
    rec.feed(e.onFrame(validFrontal(30)));
    rec.feed(e.onFrame(validFrontal(40, { yawDegrees: 30 })));
    expect(rec.result()?.type).toBe("verified");
  });

  it("smile requires a neutral->smile transition", () => {
    const e = engine({ challengeTypes: ["smile"] });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10)));
    rec.feed(e.onFrame(validFrontal(20))); // -> awaiting_challenge(smile)
    rec.feed(e.onFrame(validFrontal(30, { smilingProbability: 0.1 }))); // arm neutral
    rec.feed(e.onFrame(validFrontal(40, { smilingProbability: 0.9 }))); // smile
    expect(rec.result()?.type).toBe("verified");
  });

  it("face loss during challenge resets then recovers", () => {
    const e = engine({ challengeTypes: ["blink"], maxFaceLossResets: 2, sessionTimeoutMs: 10000, challengeTimeoutMs: 9000 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10)));
    rec.feed(e.onFrame(validFrontal(20)));
    rec.feed(e.onFrame(noFace(30)));
    expect(rec.states().at(-1)?.kind).toBe("searching_face");
    expect(rec.result()).toBeUndefined();
  });

  it("face loss beyond the cap fails the challenge", () => {
    const e = engine({ challengeTypes: ["blink"], maxFaceLossResets: 1, sessionTimeoutMs: 10000, challengeTimeoutMs: 9000 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10)));
    rec.feed(e.onFrame(validFrontal(20)));
    rec.feed(e.onFrame(noFace(30)));
    rec.feed(e.onFrame(validFrontal(40)));
    rec.feed(e.onFrame(validFrontal(50)));
    rec.feed(e.onFrame(noFace(60)));
    expect(rec.result()).toEqual({ type: "rejected", reason: "challenge_failed" });
  });

  it("low light gates face detection", () => {
    const e = engine({ minBrightness: 0.3, sessionTimeoutMs: 1000, challengeTimeoutMs: 1000 });
    const rec = new Recorder();
    rec.feed(e.start(0));
    rec.feed(e.onFrame(validFrontal(10, { brightness: 0.05 })));
    rec.feed(e.onTick(2000));
    expect(rec.result()).toEqual({ type: "rejected", reason: "low_light" });
  });
});
