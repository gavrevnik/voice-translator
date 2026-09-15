import type { Language } from "../domain/languages";

type SpeechRecognitionAlternativeLike = {
  transcript: string;
};

type SpeechRecognitionResultLike = {
  readonly isFinal: boolean;
  readonly length: number;
  readonly [index: number]: SpeechRecognitionAlternativeLike;
};

type SpeechRecognitionResultListLike = {
  readonly length: number;
  readonly [index: number]: SpeechRecognitionResultLike;
};

export type SpeechRecognitionLike = {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  maxAlternatives: number;
  onstart: (() => void) | null;
  onresult: ((event: {
    resultIndex: number;
    results: SpeechRecognitionResultListLike;
  }) => void) | null;
  onerror: ((event: { error: string; message?: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
};

export type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

type RecognitionSession = {
  recognition: SpeechRecognitionLike;
  finalTranscript: string;
  interimTranscript: string;
  started: boolean;
  settled: boolean;
  startPromise: Promise<void>;
  resultPromise: Promise<string>;
  resolveStart: () => void;
  rejectStart: (error: Error) => void;
  resolveResult: (transcript: string) => void;
  rejectResult: (error: Error) => void;
};

export class BrowserSttProvider {
  private session?: RecognitionSession;

  constructor(
    private readonly recognitionConstructor?: SpeechRecognitionConstructor | null,
  ) {}

  async start(language: Language): Promise<void> {
    if (this.session) throw new Error("Browser speech recognition is already in progress.");

    const Recognition = this.recognitionConstructor === undefined
      ? getSpeechRecognitionConstructor()
      : this.recognitionConstructor || undefined;
    if (!Recognition) {
      throw new Error(
        "Browser STT is not supported by this browser. Select Groq Whisper in Settings.",
      );
    }

    const recognition = new Recognition();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = language.ttsLocale;
    recognition.maxAlternatives = 1;

    let resolveStart!: () => void;
    let rejectStart!: (error: Error) => void;
    let resolveResult!: (transcript: string) => void;
    let rejectResult!: (error: Error) => void;
    const startPromise = new Promise<void>((resolve, reject) => {
      resolveStart = resolve;
      rejectStart = reject;
    });
    const resultPromise = new Promise<string>((resolve, reject) => {
      resolveResult = resolve;
      rejectResult = reject;
    });
    // A recognition error can arrive before the UI asks for the final result.
    // Keep its rejection handled until stop() consumes it.
    void resultPromise.catch(() => undefined);

    const session: RecognitionSession = {
      recognition,
      finalTranscript: "",
      interimTranscript: "",
      started: false,
      settled: false,
      startPromise,
      resultPromise,
      resolveStart,
      rejectStart,
      resolveResult,
      rejectResult,
    };
    this.session = session;

    recognition.onstart = () => {
      if (session.settled) return;
      session.started = true;
      session.resolveStart();
    };
    recognition.onresult = (event) => {
      const finalSegments: string[] = [];
      const interimSegments: string[] = [];
      for (let index = 0; index < event.results.length; index += 1) {
        const result = event.results[index];
        const transcript = result?.[0]?.transcript;
        if (!transcript?.trim()) continue;
        if (result.isFinal) finalSegments.push(transcript);
        else interimSegments.push(transcript);
      }
      session.finalTranscript = joinSegments(finalSegments);
      session.interimTranscript = joinSegments(interimSegments);
    };
    recognition.onerror = (event) => {
      this.finish(session, undefined, speechRecognitionError(event.error));
    };
    recognition.onend = () => {
      const transcript = joinSegments([
        session.finalTranscript,
        session.interimTranscript,
      ]);
      if (transcript) this.finish(session, transcript);
      else this.finish(session, undefined, new Error("No speech was recognized. Please try again."));
    };

    try {
      recognition.start();
      await startPromise;
    } catch (caught) {
      const error = caught instanceof Error
        ? caught
        : new Error("Browser speech recognition could not start.");
      this.finish(session, undefined, error);
      this.release(session, true);
      throw error;
    }
  }

  async stop(): Promise<string> {
    const session = this.session;
    if (!session) throw new Error("Browser speech recognition is not in progress.");

    if (!session.settled) {
      try {
        session.recognition.stop();
      } catch (caught) {
        this.finish(
          session,
          undefined,
          caught instanceof Error ? caught : new Error("Browser speech recognition could not stop."),
        );
      }
    }

    try {
      return await session.resultPromise;
    } finally {
      this.release(session);
    }
  }

  async cancel(): Promise<void> {
    const session = this.session;
    if (!session) return;

    this.finish(session, undefined, new Error("Browser speech recognition was canceled."));
    this.release(session, true);
    await session.resultPromise.catch(() => undefined);
  }

  private finish(session: RecognitionSession, transcript?: string, error?: Error): void {
    if (session.settled) return;
    session.settled = true;

    if (!session.started) {
      if (error) session.rejectStart(error);
      else session.resolveStart();
    }
    if (error) session.rejectResult(error);
    else session.resolveResult(transcript || "");
  }

  private release(session: RecognitionSession, abort = false): void {
    if (this.session !== session) return;
    if (abort) {
      try {
        session.recognition.abort();
      } catch {
        // The browser may already have disposed the recognition session.
      }
    }
    session.recognition.onstart = null;
    session.recognition.onresult = null;
    session.recognition.onerror = null;
    session.recognition.onend = null;
    this.session = undefined;
  }
}

function joinSegments(segments: string[]): string {
  return segments.join(" ").replace(/\s+/g, " ").trim();
}

export function getSpeechRecognitionConstructor(): SpeechRecognitionConstructor | undefined {
  if (typeof window === "undefined") return undefined;
  const speechWindow = window as typeof window & {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  };
  return speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition;
}

function speechRecognitionError(code: string): Error {
  switch (code) {
    case "not-allowed":
    case "service-not-allowed":
      return new Error("Microphone access or browser speech recognition was denied.");
    case "audio-capture":
      return new Error("No microphone is available for browser speech recognition.");
    case "network":
      return new Error(
        "Browser speech recognition could not reach its recognition service. Select Groq Whisper in Settings.",
      );
    case "language-not-supported":
      return new Error("The selected language is not supported by browser speech recognition.");
    case "no-speech":
      return new Error("No speech was recognized. Please try again.");
    case "aborted":
      return new Error("Browser speech recognition was canceled.");
    default:
      return new Error(`Browser speech recognition failed${code ? `: ${code}` : "."}`);
  }
}
