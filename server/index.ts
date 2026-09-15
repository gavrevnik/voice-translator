import "dotenv/config";
import path from "node:path";
import express, { type NextFunction, type Request, type Response } from "express";
import { languageRegistry, makeLanguagePair } from "../src/domain/languages";
import type {
  GeminiTranslationModelId,
  TranslationModelId,
} from "../src/domain/translator";
import { readConfig } from "./config";
import { AppError, toAppError } from "./errors";
import { GroqWhisperSttProvider } from "./stt/groq-provider";
import { GeminiTtsProvider, GEMINI_TTS_MODEL } from "./tts/gemini-provider";
import { CodexTranslationProvider } from "./translation/codex-provider";
import { GeminiTranslationProvider } from "./translation/gemini-provider";

const config = readConfig();
const app = express();
const speechRecognition = new GroqWhisperSttProvider(config.groqApiKey, config.groqSttModel);
const geminiTts = new GeminiTtsProvider(config.geminiApiKey);
const translationProviders = {
  codex: new CodexTranslationProvider(config.codexCliPath),
  gemini: new GeminiTranslationProvider(config.geminiApiKey),
};

app.disable("x-powered-by");
app.use(express.json({ limit: "32kb" }));

app.get("/api/config", (_request, response) => {
  response.json({
    languages: languageRegistry,
    defaultProvider: "codex",
    models: ["gpt-5.6-luna"],
    geminiModels: ["gemini-3.1-flash-lite", "gemini-3.5-flash-lite"],
    defaultModel: config.codexModel,
    defaultGeminiModel: config.geminiModel,
    providers: [
      {
        id: "codex",
        label: "Codex SDK",
        description: "Uses your local Codex login",
        configured: true,
        model: config.codexModel,
      },
      {
        id: "gemini",
        label: "Gemini Flash",
        description: "Uses GEMINI_API_KEY from .env",
        configured: Boolean(config.geminiApiKey),
        model: config.geminiModel,
      },
    ],
  });
});

app.get("/api/status", (_request, response) => {
  response.json({
    application: "voice-translator",
    api: true,
    groqStt: Boolean(config.groqApiKey),
    groqSttModel: config.groqSttModel,
    codex: true,
    gemini: Boolean(config.geminiApiKey),
  });
});

app.post(
  "/api/transcribe",
  express.raw({ type: ["audio/wav", "application/octet-stream"], limit: "25mb" }),
  async (request, response, next) => {
    try {
      if (!Buffer.isBuffer(request.body) || request.body.length === 0) {
        throw new AppError("The recording is empty.", 400);
      }
      const language = requireRecognitionLanguage(request.query.language);
      const startedAt = performance.now();
      const result = await speechRecognition.transcribe(request.body, language);
      const processingMs = roundMs(performance.now() - startedAt);
      console.info("[timing] stt", { language, bytes: request.body.length, processingMs });
      response.setHeader("Server-Timing", `stt;dur=${processingMs}`);
      response.json({ ...result, processingMs });
    } catch (error) {
      next(error);
    }
  },
);

app.post("/api/tts", async (request, response, next) => {
  try {
    const body = request.body as Record<string, unknown>;
    const text = requireTranscript(body.text);
    const language = requireTtsLanguage(body.language);
    const startedAt = performance.now();
    const wav = await geminiTts.synthesize(text, language);
    const processingMs = roundMs(performance.now() - startedAt);
    console.info("[timing] tts", {
      provider: "gemini",
      model: GEMINI_TTS_MODEL,
      language: language.code,
      characters: text.length,
      processingMs,
    });
    response.setHeader("Content-Type", "audio/wav");
    response.setHeader("Content-Length", wav.length);
    response.setHeader("Server-Timing", `tts;dur=${processingMs}`);
    response.send(wav);
  } catch (error) {
    next(error);
  }
});

app.post("/api/translate", async (request, response, next) => {
  try {
    const body = request.body as Record<string, unknown>;
    const providerId = requireProviderId(body.provider);
    const pair = makeLanguagePair(body.languageA, body.languageB);
    const sourceLanguage = [pair.languageA, pair.languageB].find(
      (language) => language.code === body.sourceLanguage,
    );
    if (!sourceLanguage) throw new AppError("The source language must match the selected pair.", 400);
    const text = requireTranscript(body.text);
    const options = {
      model: providerId === "codex" ? requireModel(body.model) : config.codexModel,
      geminiModel:
        providerId === "gemini" ? requireGeminiModel(body.geminiModel) : config.geminiModel,
    };
    const startedAt = performance.now();
    const result = await translationProviders[providerId].translate(
      { ...pair, sourceLanguage, text },
      options,
    );
    const processingMs = roundMs(performance.now() - startedAt);
    console.info("[timing] translation", {
      provider: providerId,
      model: providerId === "gemini" ? options.geminiModel : options.model,
      reasoning: providerId === "gemini" ? "minimal" : "low",
      sourceLanguage: sourceLanguage.code,
      targetLanguage: result.targetLanguage,
      characters: text.length,
      processingMs,
    });
    response.setHeader("Server-Timing", `translation;dur=${processingMs}`);
    response.json({ ...result, provider: providerId, processingMs });
  } catch (error) {
    next(error);
  }
});

if (process.env.NODE_ENV === "production") {
  const dist = path.join(config.projectRoot, "dist");
  app.use(express.static(dist));
  app.get("/{*path}", (_request, response) => response.sendFile(path.join(dist, "index.html")));
}

app.use((error: unknown, _request: Request, response: Response, _next: NextFunction) => {
  const appError = toAppError(error);
  if (appError.statusCode >= 500) console.error(appError);
  response.status(appError.statusCode).json({ error: appError.message });
});

app.listen(config.port, "127.0.0.1", () => {
  console.log(`Between API listening at http://127.0.0.1:${config.port}`);
});

function requireProviderId(value: unknown): "codex" | "gemini" {
  if (value === "codex" || value === "gemini") return value;
  throw new AppError("Unknown translation provider.", 400);
}

function requireTranscript(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new AppError("Transcript cannot be empty.", 400);
  }
  if (value.length > 20_000) {
    throw new AppError("Transcript is too long for this prototype.", 413);
  }
  return value.trim();
}

function requireRecognitionLanguage(value: unknown): string {
  if (typeof value !== "string") {
    throw new AppError("Choose a source language before recording.", 400);
  }
  const language = languageRegistry.find((item) => item.whisperCode === value);
  if (!language) throw new AppError("Unsupported recognition language.", 400);
  return language.whisperCode;
}

function requireTtsLanguage(value: unknown) {
  if (typeof value !== "string") {
    throw new AppError("Choose a speech language before playback.", 400);
  }
  const language = languageRegistry.find((item) => item.code === value);
  if (!language) throw new AppError("Unsupported speech language.", 400);
  return language;
}

function requireModel(value: unknown): TranslationModelId {
  if (value === "gpt-5.6-luna") return value;
  throw new AppError("Unsupported translation model.", 400);
}

function requireGeminiModel(value: unknown): GeminiTranslationModelId {
  if (value === "gemini-3.1-flash-lite" || value === "gemini-3.5-flash-lite") {
    return value;
  }
  throw new AppError("Unsupported Gemini translation model.", 400);
}

function roundMs(value: number): number {
  return Math.round(value * 10) / 10;
}
