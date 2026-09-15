import { describe, expect, it } from "vitest";
import { languageRegistry } from "../domain/languages";
import {
  BrowserSttProvider,
  type SpeechRecognitionLike,
} from "./stt";

class FakeRecognition implements SpeechRecognitionLike {
  static current?: FakeRecognition;

  continuous = false;
  interimResults = false;
  lang = "";
  maxAlternatives = 0;
  onstart: (() => void) | null = null;
  onresult: SpeechRecognitionLike["onresult"] = null;
  onerror: SpeechRecognitionLike["onerror"] = null;
  onend: (() => void) | null = null;
  stopped = false;
  aborted = false;

  constructor() {
    FakeRecognition.current = this;
  }

  start(): void {}

  stop(): void {
    this.stopped = true;
  }

  abort(): void {
    this.aborted = true;
  }

  emitResults(
    resultIndex: number,
    ...segments: Array<{ transcript: string; isFinal: boolean }>
  ): void {
    const results = segments.map(({ transcript, isFinal }) => ({
      0: { transcript },
      isFinal,
      length: 1,
    }));
    this.onresult?.({ resultIndex, results });
  }

  emitFinalResult(...segments: string[]): void {
    this.emitResults(
      0,
      ...segments.map((transcript) => ({ transcript, isFinal: true })),
    );
  }
}

describe("Browser STT", () => {
  it("uses SpeechRecognition with the selected locale and returns its transcript", async () => {
    const provider = new BrowserSttProvider(FakeRecognition);
    const starting = provider.start(languageRegistry[1]);
    const recognition = FakeRecognition.current!;

    recognition.onstart?.();
    await starting;
    expect(recognition.lang).toBe("en-US");
    expect(recognition.continuous).toBe(true);
    expect(recognition.interimResults).toBe(true);

    const stopping = provider.stop();
    recognition.emitFinalResult(" hello ", "world");
    recognition.onend?.();

    await expect(stopping).resolves.toBe("hello world");
    expect(recognition.stopped).toBe(true);
  });

  it("does not append an interim hypothesis removed from the current results", async () => {
    const provider = new BrowserSttProvider(FakeRecognition);
    const starting = provider.start(languageRegistry[0]);
    const recognition = FakeRecognition.current!;

    recognition.onstart?.();
    await starting;
    recognition.emitResults(
      0,
      { transcript: "Добрый день", isFinal: true },
      { transcript: "пройти в библиоте", isFinal: false },
    );

    const stopping = provider.stop();
    recognition.emitResults(
      1,
      { transcript: "Добрый день", isFinal: true },
    );
    recognition.onend?.();

    await expect(stopping).resolves.toBe("Добрый день");
  });

  it("explains how to fall back when SpeechRecognition is unavailable", async () => {
    const provider = new BrowserSttProvider(null);

    await expect(provider.start(languageRegistry[0])).rejects.toThrow(
      "Select Groq Whisper in Settings",
    );
  });

  it("maps permission errors to a useful message", async () => {
    const provider = new BrowserSttProvider(FakeRecognition);
    const starting = provider.start(languageRegistry[0]);
    const recognition = FakeRecognition.current!;

    recognition.onerror?.({ error: "not-allowed" });

    await expect(starting).rejects.toThrow("was denied");
    expect(recognition.aborted).toBe(true);
  });
});
