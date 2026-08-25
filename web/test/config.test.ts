import { describe, expect, it } from "vitest";
import { DEFAULT_CONFIG, resolveConfig, validateConfig } from "../src/core";

describe("config", () => {
  it("defaults are valid", () => {
    expect(() => validateConfig(DEFAULT_CONFIG)).not.toThrow();
  });
  it("rejects bad face ratio", () => {
    expect(() => resolveConfig({ minFaceRatio: 1.5 })).toThrow();
  });
  it("rejects empty challenge set", () => {
    expect(() => resolveConfig({ challengeTypes: [] })).toThrow();
  });
  it("rejects session shorter than challenge", () => {
    expect(() => resolveConfig({ sessionTimeoutMs: 100, challengeTimeoutMs: 5000 })).toThrow();
  });
  it("rejects eye threshold inversion", () => {
    expect(() => resolveConfig({ eyeClosedThreshold: 0.8, eyeOpenThreshold: 0.2 })).toThrow();
  });
  it("merges partial over defaults", () => {
    const c = resolveConfig({ minFaceRatio: 0.3 });
    expect(c.minFaceRatio).toBe(0.3);
    expect(c.maxYawDegrees).toBe(DEFAULT_CONFIG.maxYawDegrees);
  });
});
