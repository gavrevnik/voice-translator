import type { Language, LanguagePair } from "./languages";

export type TranslationProviderId = "codex" | "gemini";
export type TranslationModelId = "gpt-5.6-luna" | "gpt-5.6-terra";
export type GeminiTranslationModelId =
  | "gemini-3.1-flash-lite"
  | "gemini-3.5-flash-lite";

export type TranslationSelectionId =
  | "codex:gpt-5.6-luna"
  | "codex:gpt-5.6-terra"
  | "gemini:gemini-3.1-flash-lite"
  | "gemini:gemini-3.5-flash-lite";

export type TranslationSelection = {
  id: TranslationSelectionId;
  label: string;
  provider: TranslationProviderId;
  model?: TranslationModelId;
  geminiModel?: GeminiTranslationModelId;
};

export const translationSelections: readonly TranslationSelection[] = [
  {
    id: "codex:gpt-5.6-luna",
    label: "Codex GPT-5.6 Luna",
    provider: "codex",
    model: "gpt-5.6-luna",
  },
  {
    id: "codex:gpt-5.6-terra",
    label: "Codex GPT-5.6 Terra",
    provider: "codex",
    model: "gpt-5.6-terra",
  },
  {
    id: "gemini:gemini-3.1-flash-lite",
    label: "Gemini Flash 3.1",
    provider: "gemini",
    geminiModel: "gemini-3.1-flash-lite",
  },
  {
    id: "gemini:gemini-3.5-flash-lite",
    label: "Gemini Flash 3.5",
    provider: "gemini",
    geminiModel: "gemini-3.5-flash-lite",
  },
] as const;

export type TranslationRequest = LanguagePair & {
  text: string;
  sourceLanguage: Language;
};

export type TranslationOptions = {
  model: TranslationModelId;
  geminiModel: GeminiTranslationModelId;
};

export type TranslationResult = {
  detectedSourceLanguage: string;
  targetLanguage: string;
  translatedText: string;
};

export type TranslationProvider = {
  readonly id: TranslationProviderId;
  translate(
    request: TranslationRequest,
    options: TranslationOptions,
  ): Promise<TranslationResult>;
};

export const translationResultSchema = {
  type: "object",
  properties: {
    detectedSourceLanguage: { type: "string" },
    targetLanguage: { type: "string" },
    translatedText: { type: "string" },
  },
  required: ["detectedSourceLanguage", "targetLanguage", "translatedText"],
  additionalProperties: false,
} as const;
