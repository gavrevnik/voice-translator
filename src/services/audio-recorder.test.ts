import { describe, expect, it } from "vitest";
import { downsample, encodeWav, mergeSamples } from "./audio-recorder";

describe("browser WAV recorder helpers", () => {
  it("merges and downsamples PCM chunks", () => {
    const merged = mergeSamples([new Float32Array([1, 0]), new Float32Array([-1, 0])]);
    expect([...merged]).toEqual([1, 0, -1, 0]);
    expect([...downsample(merged, 4, 2)]).toEqual([0.5, -0.5]);
  });

  it("writes a mono 16-bit RIFF/WAVE file", async () => {
    const wav = encodeWav(new Float32Array([0, 0.5, -0.5]), 16_000);
    const bytes = new Uint8Array(await wav.arrayBuffer());
    expect(new TextDecoder().decode(bytes.slice(0, 4))).toBe("RIFF");
    expect(new TextDecoder().decode(bytes.slice(8, 12))).toBe("WAVE");
    expect(wav.type).toBe("audio/wav");
  });
});
