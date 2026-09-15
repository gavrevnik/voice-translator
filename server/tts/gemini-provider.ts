import type { Language } from "../../src/domain/languages";
import { AppError } from "../errors";

export const GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview";

type GeminiTtsResponse = {
  candidates?: Array<{
    content?: {
      parts?: Array<{
        inlineData?: { data?: string; mimeType?: string };
      }>;
    };
    finishReason?: string;
  }>;
  error?: { message?: string };
};

export class GeminiTtsProvider {
  constructor(private readonly apiKey: string | undefined) {}

  async synthesize(text: string, language: Language): Promise<Buffer> {
    if (!this.apiKey?.trim()) {
      throw new AppError(
        "Gemini API key is not configured. Add GEMINI_API_KEY to .env.",
        503,
      );
    }

    try {
      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_TTS_MODEL}:generateContent`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "x-goog-api-key": this.apiKey.trim(),
          },
          body: JSON.stringify({
            contents: [{ role: "user", parts: [{ text: buildTtsPrompt(text, language) }] }],
            generationConfig: {
              responseModalities: ["AUDIO"],
              speechConfig: {
                voiceConfig: {
                  prebuiltVoiceConfig: { voiceName: "Kore" },
                },
              },
            },
          }),
          signal: AbortSignal.timeout(90_000),
        },
      );

      const rawBody = await response.text();
      const body = parseResponse(rawBody);
      if (!response.ok) {
        throw new AppError(
          body.error?.message || `Gemini TTS returned HTTP ${response.status}.`,
          502,
        );
      }

      const generated = extractAudio(body);
      return encodePcm16Wav(generated.pcm, generated.sampleRate);
    } catch (error) {
      if (error instanceof AppError) throw error;
      const message = error instanceof Error ? error.message : String(error);
      throw new AppError(`Gemini TTS failed. ${message}`, 502, { cause: error });
    }
  }
}

function buildTtsPrompt(text: string, language: Language): string {
  return `Read the text below aloud naturally in ${language.displayName} (${language.code}, ${language.ttsLocale}).
Speak only the quoted text, without an introduction, answer, translation, or commentary.

<text>
${escapePromptValue(text)}
</text>`;
}

function escapePromptValue(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function parseResponse(rawBody: string): GeminiTtsResponse {
  try {
    return JSON.parse(rawBody) as GeminiTtsResponse;
  } catch (error) {
    throw new AppError("Gemini TTS returned an invalid response.", 502, { cause: error });
  }
}

function extractAudio(body: GeminiTtsResponse): { pcm: Buffer; sampleRate: number } {
  const parts = body.candidates?.[0]?.content?.parts || [];
  const chunks: Buffer[] = [];
  let sampleRate = 24_000;

  for (const part of parts) {
    const encoded = part.inlineData?.data;
    if (!encoded) continue;
    const rate = /rate=(\d+)/i.exec(part.inlineData?.mimeType || "")?.[1];
    if (rate) sampleRate = Number(rate);
    chunks.push(Buffer.from(encoded, "base64"));
  }

  const pcm = Buffer.concat(chunks);
  if (!pcm.length) {
    const reason = body.candidates?.[0]?.finishReason;
    throw new AppError(
      `Gemini TTS returned no audio${reason ? ` (${reason})` : ""}.`,
      502,
    );
  }
  return { pcm, sampleRate };
}

export function encodePcm16Wav(pcm: Buffer, sampleRate: number): Buffer {
  const wav = Buffer.alloc(44 + pcm.length);
  wav.write("RIFF", 0);
  wav.writeUInt32LE(36 + pcm.length, 4);
  wav.write("WAVE", 8);
  wav.write("fmt ", 12);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(sampleRate, 24);
  wav.writeUInt32LE(sampleRate * 2, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write("data", 36);
  wav.writeUInt32LE(pcm.length, 40);
  pcm.copy(wav, 44);
  return wav;
}
