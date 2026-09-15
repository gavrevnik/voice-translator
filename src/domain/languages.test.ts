import { describe, expect, it } from "vitest";
import { getLanguage, languageRegistry, makeLanguagePair } from "./languages";

describe("language registry", () => {
  it("ships the Russian/English milestone without coupling the pair to the pipeline", () => {
    expect(getLanguage("ru")?.whisperCode).toBe("ru");
    expect(getLanguage("en")?.ttsLocale).toBe("en-US");
    expect(makeLanguagePair("ru", "en")).toMatchObject({
      languageA: { code: "ru" },
      languageB: { code: "en" },
    });
  });

  it("rejects duplicate languages", () => {
    expect(() => makeLanguagePair("ru", "ru")).toThrow("different languages");
  });

  it("offers only the five supported conversation languages", () => {
    expect(languageRegistry.map((language) => language.code).sort()).toEqual([
      "en",
      "es",
      "ro",
      "ru",
      "sr",
    ]);
  });
});
