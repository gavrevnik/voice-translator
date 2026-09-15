import type { Language } from "../domain/languages";
import type {
  GeminiTranslationModelId,
  TranslationModelId,
  TranslationProviderId,
  TranslationResult,
} from "../domain/translator";

export type ProviderConfig = {
  id: TranslationProviderId;
  label: string;
  description: string;
  configured: boolean;
  model: string;
};

export type ClientConfig = {
  languages: Language[];
  defaultProvider: TranslationProviderId;
  models: TranslationModelId[];
  geminiModels: GeminiTranslationModelId[];
  defaultModel: TranslationModelId;
  defaultGeminiModel: GeminiTranslationModelId;
  providers: ProviderConfig[];
};

export type ServiceStatus = {
  application: "voice-translator";
  api: boolean;
  groqStt: boolean;
  groqSttModel: "whisper-large-v3";
  codex: boolean;
  gemini: boolean;
};

export type TranscriptionResult = {
  text: string;
  detectedLanguage?: string;
  durationSeconds?: number;
  processingMs?: number;
};

export async function loadConfig(): Promise<ClientConfig> {
  return requestJson<ClientConfig>("/api/config");
}

export async function loadStatus(): Promise<ServiceStatus> {
  return requestJson<ServiceStatus>("/api/status");
}

export async function transcribe(
  recording: Blob,
  language: string,
): Promise<TranscriptionResult> {
  return requestJson<TranscriptionResult>(`/api/transcribe?language=${encodeURIComponent(language)}`, {
    method: "POST",
    headers: { "Content-Type": "audio/wav" },
    body: recording,
  });
}

export async function translate(input: {
  provider: TranslationProviderId;
  languageA: string;
  languageB: string;
  sourceLanguage: string;
  text: string;
  model: TranslationModelId;
  geminiModel: GeminiTranslationModelId;
}): Promise<TranslationResult & { provider: TranslationProviderId; processingMs?: number }> {
  return requestJson("/api/translate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (error) {
    throw new Error("The local backend is not reachable. Start it with npm run dev.", {
      cause: error,
    });
  }

  const body = (await response.json().catch(() => ({}))) as Record<string, unknown>;
  if (!response.ok) {
    throw new Error(typeof body.error === "string" ? body.error : `Request failed (${response.status}).`);
  }
  return body as T;
}
