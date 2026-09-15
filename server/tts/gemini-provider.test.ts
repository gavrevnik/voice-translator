import { afterEach, describe, expect, it, vi } from "vitest";
import { languageRegistry } from "../../src/domain/languages";
import { GeminiTtsProvider, GEMINI_TTS_MODEL } from "./gemini-provider";

afterEach(() => vi.unstubAllGlobals());

describe("Gemini web TTS", () => {
  it("keeps spoken text in a user turn and returns WAV audio", async () => {
    const pcm = Buffer.from([1, 2, 3, 4]);
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          candidates: [{
            content: {
              parts: [{
                inlineData: {
                  data: pcm.toString("base64"),
                  mimeType: "audio/L16;codec=pcm;rate=24000",
                },
              }],
            },
          }],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    const language = languageRegistry.find((item) => item.code === "sr")!;

    const wav = await new GeminiTtsProvider("test-key").synthesize(
      "Where is the station?",
      language,
    );

    expect(fetchMock.mock.calls[0][0]).toContain(`/${GEMINI_TTS_MODEL}:generateContent`);
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    const payload = JSON.parse(request.body as string) as {
      contents: Array<{ role: string; parts: Array<{ text: string }> }>;
    };
    expect(payload.contents[0].role).toBe("user");
    expect(payload.contents[0].parts[0].text).toContain("<text>\nWhere is the station?\n</text>");
    expect(wav.toString("ascii", 0, 4)).toBe("RIFF");
    expect(wav.toString("ascii", 8, 12)).toBe("WAVE");
    expect(wav.readUInt32LE(24)).toBe(24_000);
    expect(wav.subarray(44)).toEqual(pcm);
  });
});
