import type { Language } from "../domain/languages";

export type TtsProvider = {
  speak(text: string, language: Language, onStart?: () => void): Promise<void>;
  stop(): void;
};

export class BrowserTtsProvider implements TtsProvider {
  speak(text: string, language: Language, onStart?: () => void): Promise<void> {
    if (!("speechSynthesis" in window)) {
      return Promise.reject(new Error("Text-to-speech is not supported by this browser."));
    }

    this.stop();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = language.ttsLocale;
    utterance.rate = 0.96;
    utterance.pitch = 1;

    const voice = chooseVoice(window.speechSynthesis.getVoices(), language.ttsLocale);
    if (voice) utterance.voice = voice;

    return new Promise((resolve, reject) => {
      utterance.onstart = () => onStart?.();
      utterance.onend = () => resolve();
      utterance.onerror = (event) => {
        if (event.error === "canceled" || event.error === "interrupted") resolve();
        else reject(new Error(`Speech playback failed: ${event.error}`));
      };
      window.speechSynthesis.speak(utterance);
    });
  }

  stop(): void {
    if ("speechSynthesis" in window) window.speechSynthesis.cancel();
  }
}

export class GeminiTtsProvider implements TtsProvider {
  private controller?: AbortController;
  private audio?: HTMLAudioElement;
  private objectUrl?: string;
  private finishPlayback?: () => void;

  async speak(text: string, language: Language, onStart?: () => void): Promise<void> {
    this.stop();
    const controller = new AbortController();
    this.controller = controller;
    const response = await fetch("/api/tts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text, language: language.code }),
      signal: controller.signal,
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as Record<string, unknown>;
      throw new Error(
        typeof body.error === "string" ? body.error : `Speech generation failed (${response.status}).`,
      );
    }
    if (this.controller !== controller) return;

    const objectUrl = URL.createObjectURL(await response.blob());
    const audio = new Audio(objectUrl);
    this.objectUrl = objectUrl;
    this.audio = audio;

    await new Promise<void>((resolve, reject) => {
      this.finishPlayback = resolve;
      audio.onplay = () => onStart?.();
      audio.onended = () => resolve();
      audio.onerror = () => reject(new Error("Gemini speech playback failed."));
      audio.play().catch(reject);
    }).finally(() => {
      if (this.audio === audio) this.clearAudio();
      if (this.controller === controller) this.controller = undefined;
    });
  }

  stop(): void {
    this.controller?.abort();
    this.controller = undefined;
    this.audio?.pause();
    this.finishPlayback?.();
    this.clearAudio();
  }

  private clearAudio(): void {
    if (this.objectUrl) URL.revokeObjectURL(this.objectUrl);
    this.audio = undefined;
    this.objectUrl = undefined;
    this.finishPlayback = undefined;
  }
}

export function chooseVoice(voices: SpeechSynthesisVoice[], locale: string) {
  const exact = voices.filter((voice) => voice.lang.toLowerCase() === locale.toLowerCase());
  const language = locale.split("-")[0].toLowerCase();
  const matching = exact.length
    ? exact
    : voices.filter((voice) => voice.lang.toLowerCase().startsWith(`${language}-`));
  return matching.find((voice) => voice.localService) || matching[0];
}
