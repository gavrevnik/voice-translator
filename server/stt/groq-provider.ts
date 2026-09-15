import { AppError } from "../errors";

const GROQ_TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
const FREE_TIER_FILE_LIMIT_BYTES = 25 * 1024 * 1024;

export type TranscriptionResult = {
  text: string;
  detectedLanguage?: string;
  durationSeconds?: number;
};

export type SttProvider = {
  transcribe(wav: Buffer, language: string): Promise<TranscriptionResult>;
};

export class GroqWhisperSttProvider implements SttProvider {
  constructor(
    private readonly apiKey: string | undefined,
    private readonly model: "whisper-large-v3",
  ) {}

  async transcribe(wav: Buffer, language: string): Promise<TranscriptionResult> {
    if (!isWav(wav)) {
      throw new AppError("The browser did not send a valid WAV recording.", 400);
    }
    if (!this.apiKey) {
      throw new AppError("Groq API key is not configured. Add GROQ_API_KEY to .env.", 503);
    }
    if (wav.byteLength > FREE_TIER_FILE_LIMIT_BYTES) {
      throw new AppError("The recording exceeds the 25 MB Groq free-tier limit.", 413);
    }

    const form = new FormData();
    const bytes = new Uint8Array(wav.byteLength);
    bytes.set(wav);
    form.set("file", new Blob([bytes.buffer], { type: "audio/wav" }), "recording.wav");
    form.set("model", this.model);
    form.set("language", language);
    form.set("response_format", "verbose_json");
    form.set("temperature", "0");

    let response: Response;
    try {
      response = await fetch(GROQ_TRANSCRIPTIONS_URL, {
        method: "POST",
        headers: { Authorization: `Bearer ${this.apiKey}` },
        body: form,
        signal: AbortSignal.timeout(60_000),
      });
    } catch (error) {
      throw new AppError("Cannot reach Groq speech recognition.", 503, { cause: error });
    }

    if (!response.ok) {
      const details = (await response.text()).slice(0, 400);
      throw new AppError(
        `Groq speech recognition returned ${response.status}${details ? `: ${details}` : "."}`,
        502,
      );
    }

    const payload = (await response.json()) as Record<string, unknown>;
    const text = typeof payload.text === "string" ? payload.text.trim() : "";
    if (!text) {
      throw new AppError("No speech was recognized. Try speaking closer to the microphone.", 422);
    }

    return {
      text,
      detectedLanguage: typeof payload.language === "string" ? payload.language : undefined,
      durationSeconds: typeof payload.duration === "number" ? payload.duration : undefined,
    };
  }
}

export function isWav(buffer: Buffer): boolean {
  return (
    buffer.length >= 44 &&
    buffer.toString("ascii", 0, 4) === "RIFF" &&
    buffer.toString("ascii", 8, 12) === "WAVE"
  );
}
