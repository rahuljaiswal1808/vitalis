import type { EngineEvent, FaceSignals, LivenessState } from "../src/core";

export function validFrontal(t: number, o: Partial<FaceSignals> = {}): FaceSignals {
  return {
    faceCount: 1,
    boundingBoxRatio: 0.4,
    yawDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    leftEyeOpenProbability: 0.9,
    rightEyeOpenProbability: 0.9,
    smilingProbability: 0.1,
    brightness: 0.6,
    timestampMs: t,
    ...o,
  };
}

export function noFace(t: number): FaceSignals {
  return { ...validFrontal(t), faceCount: 0, boundingBoxRatio: 0 };
}

export function multiFace(t: number): FaceSignals {
  return { ...validFrontal(t), faceCount: 2 };
}

export class Recorder {
  events: EngineEvent[] = [];
  feed(list: EngineEvent[]): void {
    this.events.push(...list);
  }
  states(): LivenessState[] {
    return this.events.filter((e) => e.type === "state_changed").map((e) => (e as any).state);
  }
  result(): EngineEvent | undefined {
    return [...this.events].reverse().find((e) => e.type === "verified" || e.type === "rejected");
  }
}
