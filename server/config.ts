import path from "node:path";
import type {
  GeminiTranslationModelId,
  TranslationModelId,
} from "../src/domain/translator";

export type AppConfig = {
  port: number;
  groqApiKey?: string;
  groqSttModel: "whisper-large-v3";
  codexModel: TranslationModelId;
  codexCliPath?: string;
  geminiApiKey?: string;
  geminiModel: GeminiTranslationModelId;
  projectRoot: string;
};

function optional(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

export function readConfig(): AppConfig {
  const projectRoot = path.resolve(process.cwd());

  return {
    port: Number(process.env.PORT || 8787),
    groqApiKey: optional(process.env.GROQ_API_KEY),
    groqSttModel: "whisper-large-v3",
    codexModel: readModel(process.env.CODEX_MODEL),
    codexCliPath: optional(process.env.CODEX_CLI_PATH),
    geminiApiKey: optional(process.env.GEMINI_API_KEY),
    geminiModel: "gemini-3.1-flash-lite",
    projectRoot,
  };
}

function readModel(value: string | undefined): TranslationModelId {
  return optional(value) === "gpt-5.6-terra" ? "gpt-5.6-terra" : "gpt-5.6-luna";
}
