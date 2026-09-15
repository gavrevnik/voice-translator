export type Language = {
  code: string;
  displayName: string;
  nativeName: string;
  whisperCode: string;
  ttsLocale: string;
  shortLabel: string;
  speakLabel: string;
  stopLabel: string;
};

export type LanguagePair = {
  languageA: Language;
  languageB: Language;
};

export const languageRegistry: readonly Language[] = [
  {
    code: "ru",
    displayName: "Russian",
    nativeName: "Русский",
    whisperCode: "ru",
    ttsLocale: "ru-RU",
    shortLabel: "RU",
    speakLabel: "Говорить",
    stopLabel: "Стоп",
  },
  {
    code: "en",
    displayName: "English",
    nativeName: "English",
    whisperCode: "en",
    ttsLocale: "en-US",
    shortLabel: "EN",
    speakLabel: "Speak",
    stopLabel: "Stop",
  },
  {
    code: "ro",
    displayName: "Romanian",
    nativeName: "Română",
    whisperCode: "ro",
    ttsLocale: "ro-RO",
    shortLabel: "RO",
    speakLabel: "Vorbește",
    stopLabel: "Oprește",
  },
  {
    code: "es",
    displayName: "Spanish",
    nativeName: "Español",
    whisperCode: "es",
    ttsLocale: "es-ES",
    shortLabel: "ES",
    speakLabel: "Habla",
    stopLabel: "Detener",
  },
  {
    code: "sr",
    displayName: "Serbian",
    nativeName: "Српски",
    whisperCode: "sr",
    ttsLocale: "sr-RS",
    shortLabel: "SR",
    speakLabel: "Говори",
    stopLabel: "Заустави",
  },
] as const;

export function getLanguage(code: string): Language | undefined {
  return languageRegistry.find((language) => language.code === code);
}

export function requireLanguage(code: unknown): Language {
  if (typeof code !== "string") {
    throw new Error("Language code must be a string.");
  }

  const language = getLanguage(code);
  if (!language) {
    throw new Error(`Unsupported language: ${code}`);
  }
  return language;
}

export function makeLanguagePair(languageACode: unknown, languageBCode: unknown): LanguagePair {
  const languageA = requireLanguage(languageACode);
  const languageB = requireLanguage(languageBCode);

  if (languageA.code === languageB.code) {
    throw new Error("Choose two different languages.");
  }

  return { languageA, languageB };
}
