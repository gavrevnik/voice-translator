import { afterEach, describe, expect, it, vi } from "vitest";
import { makeLanguagePair } from "../../src/domain/languages";
import type { GeminiTranslationModelId } from "../../src/domain/translator";
import { GeminiTranslationProvider } from "./gemini-provider";

afterEach(() => vi.unstubAllGlobals());

describe("Gemini translation", () => {
  it.each<GeminiTranslationModelId>([
    "gemini-3.1-flash-lite",
    "gemini-3.5-flash-lite",
  ])("uses %s with minimal thinking", async (geminiModel) => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          candidates: [
            {
              content: {
                parts: [
                  {
                    text: JSON.stringify({
                      detectedSourceLanguage: "en",
                      targetLanguage: "es",
                      translatedText: "Hola",
                    }),
                  },
                ],
              },
            },
          ],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    const pair = makeLanguagePair("en", "es");

    const result = await new GeminiTranslationProvider("test-key").translate(
      { ...pair, sourceLanguage: pair.languageA, text: "Hello" },
      { model: "gpt-5.6-luna", geminiModel },
    );

    expect(fetchMock.mock.calls[0][0]).toBe(
      `https://generativelanguage.googleapis.com/v1beta/models/${geminiModel}:generateContent`,
    );
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    const payload = JSON.parse(request.body as string) as {
      systemInstruction: { parts: Array<{ text: string }> };
      contents: Array<{ role: string; parts: Array<{ text: string }> }>;
      generationConfig: { thinkingConfig: { thinkingLevel: string } };
    };
    expect(payload.systemInstruction.parts[0].text).toContain("source_language = English");
    expect(payload.contents[0]).toEqual({ role: "user", parts: [{ text: "Hello" }] });
    expect(payload.generationConfig.thinkingConfig.thinkingLevel).toBe("minimal");
    expect(result.translatedText).toBe("Hola");
  });
});
