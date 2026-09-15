import type {
  TranslationOptions,
  TranslationProvider,
  TranslationRequest,
  TranslationResult,
} from "../../src/domain/translator";
import { AppError } from "../errors";
import {
  buildTranslationPrompt,
  createPairOutputSchema,
  normalizeTranslationResult,
} from "./prompt";

type GeminiResponse = {
  candidates?: Array<{
    content?: { parts?: Array<{ text?: string }> };
    finishReason?: string;
  }>;
  error?: { message?: string };
  promptFeedback?: { blockReason?: string };
};

export class GeminiTranslationProvider implements TranslationProvider {
  readonly id = "gemini" as const;

  constructor(
    private readonly apiKey: string | undefined,
  ) {}

  async translate(
    request: TranslationRequest,
    options: TranslationOptions,
  ): Promise<TranslationResult> {
    if (!this.apiKey?.trim()) {
      throw new AppError(
        "Gemini API key is not configured. Add GEMINI_API_KEY to .env.",
        503,
      );
    }

    try {
      const prompt = buildTranslationPrompt(request);
      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${options.geminiModel}:generateContent`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "x-goog-api-key": this.apiKey.trim(),
          },
          body: JSON.stringify({
            systemInstruction: {
              parts: [{ text: prompt.systemInstruction }],
            },
            contents: [
              {
                role: "user",
                parts: [{ text: prompt.userInput }],
              },
            ],
            generationConfig: {
              temperature: 0,
              maxOutputTokens: 2_048,
              responseMimeType: "application/json",
              responseJsonSchema: createPairOutputSchema(request),
              thinkingConfig: { thinkingLevel: "minimal" },
            },
          }),
          signal: AbortSignal.timeout(90_000),
        },
      );

      const rawBody = await response.text();
      const body = parseResponse(rawBody);
      if (!response.ok) {
        throw new AppError(
          body.error?.message || `Gemini API returned HTTP ${response.status}.`,
          502,
        );
      }

      const structuredText = body.candidates?.[0]?.content?.parts
        ?.map((part) => part.text || "")
        .join("")
        .trim();
      if (!structuredText) {
        const reason = body.promptFeedback?.blockReason || body.candidates?.[0]?.finishReason;
        throw new AppError(
          `Gemini returned no translation${reason ? ` (${reason})` : ""}.`,
          502,
        );
      }
      return normalizeTranslationResult(JSON.parse(structuredText), request);
    } catch (error) {
      if (error instanceof AppError) throw error;
      const message = error instanceof Error ? error.message : String(error);
      throw new AppError(`Gemini translation failed. ${message}`, 502, { cause: error });
    }
  }
}

function parseResponse(rawBody: string): GeminiResponse {
  try {
    return JSON.parse(rawBody) as GeminiResponse;
  } catch (error) {
    throw new AppError("Gemini returned an invalid response.", 502, { cause: error });
  }
}
