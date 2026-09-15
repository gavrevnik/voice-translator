import { afterEach, describe, expect, it, vi } from "vitest";
import { GroqWhisperSttProvider, isWav } from "./groq-provider";

afterEach(() => vi.unstubAllGlobals());

function makeWav(): Buffer {
  const wav = Buffer.alloc(44);
  wav.write("RIFF", 0);
  wav.write("WAVE", 8);
  return wav;
}

describe("Groq Whisper STT", () => {
  it("accepts a RIFF/WAVE header", () => {
    expect(isWav(makeWav())).toBe(true);
  });

  it("rejects browser payloads with another container", () => {
    expect(isWav(Buffer.from("webm"))).toBe(false);
  });

  it("uses whisper-large-v3 and forwards the selected language", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ text: "Добар дан", language: "sr", duration: 1.2 }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await new GroqWhisperSttProvider("test-key", "whisper-large-v3")
      .transcribe(makeWav(), "sr");

    expect(fetchMock).toHaveBeenCalledWith(
      "https://api.groq.com/openai/v1/audio/transcriptions",
      expect.objectContaining({ method: "POST" }),
    );
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    const form = request.body as FormData;
    expect(form.get("model")).toBe("whisper-large-v3");
    expect(form.get("language")).toBe("sr");
    expect(result).toEqual({
      text: "Добар дан",
      detectedLanguage: "sr",
      durationSeconds: 1.2,
    });
  });

  it("fails before the network call when GROQ_API_KEY is missing", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      new GroqWhisperSttProvider(undefined, "whisper-large-v3").transcribe(makeWav(), "en"),
    ).rejects.toMatchObject({ statusCode: 503 });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
