import { useEffect, useRef, useState } from "react";
import { CloseIcon, MicIcon, PlayIcon, SettingsIcon, StopIcon } from "./components/Icons";
import { languageRegistry, type Language } from "./domain/languages";
import type { VoiceTranslatorState } from "./domain/state";
import {
  translationSelections,
  type GeminiTranslationModelId,
  type TranslationModelId,
  type TranslationResult,
  type TranslationSelectionId,
} from "./domain/translator";
import {
  loadConfig,
  transcribe,
  translate,
  type ClientConfig,
  type ProviderConfig,
} from "./services/api";
import { BrowserAudioRecorder } from "./services/audio-recorder";
import { BrowserTtsProvider, GeminiTtsProvider, type TtsProvider } from "./services/tts";

type LanguageSide = "a" | "b";
type TtsSelectionId = "browser" | "gemini";

const models: TranslationModelId[] = ["gpt-5.6-luna", "gpt-5.6-terra"];
const geminiModels: GeminiTranslationModelId[] = [
  "gemini-3.1-flash-lite",
  "gemini-3.5-flash-lite",
];
const fallbackProviders: ProviderConfig[] = [
  {
    id: "codex",
    label: "Codex SDK",
    description: "Uses your local Codex login",
    configured: true,
    model: "gpt-5.6-luna",
  },
  {
    id: "gemini",
    label: "Gemini Flash",
    description: "Uses GEMINI_API_KEY from .env",
    configured: false,
    model: "gemini-3.1-flash-lite",
  },
];

const fallbackConfig: ClientConfig = {
  languages: [...languageRegistry],
  defaultProvider: "codex",
  models,
  geminiModels,
  defaultModel: "gpt-5.6-luna",
  defaultGeminiModel: "gemini-3.1-flash-lite",
  providers: fallbackProviders,
};

function readStoredTranslation(): TranslationSelectionId {
  const stored = localStorage.getItem("between.translation");
  const current = translationSelections.find((selection) => selection.id === stored);
  if (current) return current.id;

  const legacyProvider = localStorage.getItem("between.provider");
  if (legacyProvider === "gemini") {
    return localStorage.getItem("between.geminiModel") === "gemini-3.5-flash-lite"
      ? "gemini:gemini-3.5-flash-lite"
      : "gemini:gemini-3.1-flash-lite";
  }
  return localStorage.getItem("between.model") === "gpt-5.6-terra"
    ? "codex:gpt-5.6-terra"
    : "codex:gpt-5.6-luna";
}

function readStoredTts(): TtsSelectionId {
  return localStorage.getItem("between.tts") === "gemini" ? "gemini" : "browser";
}

function toMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Something went wrong.";
}

export default function App() {
  const recorder = useRef(new BrowserAudioRecorder());
  const ttsProviders = useRef({
    browser: new BrowserTtsProvider(),
    gemini: new GeminiTtsProvider(),
  });
  const activeTts = useRef<TtsProvider | undefined>(undefined);
  const operationId = useRef(0);
  const [config, setConfig] = useState<ClientConfig>(fallbackConfig);
  const [state, setState] = useState<VoiceTranslatorState>("ready");
  const [languageA, setLanguageA] = useState("ru");
  const [languageB, setLanguageB] = useState("en");
  const [translationSelectionId, setTranslationSelectionId] = useState<TranslationSelectionId>(
    readStoredTranslation,
  );
  const [ttsSelection, setTtsSelection] = useState<TtsSelectionId>(readStoredTts);
  const [textA, setTextA] = useState("");
  const [textB, setTextB] = useState("");
  const [result, setResult] = useState<TranslationResult>();
  const [resultSide, setResultSide] = useState<LanguageSide>();
  const [recordingSide, setRecordingSide] = useState<LanguageSide>();
  const [error, setError] = useState<string>();
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [settingsOpen, setSettingsOpen] = useState(false);

  const languages = config.languages.length ? config.languages : [...languageRegistry];
  const firstLanguage = getLanguageFromList(languageA, languages);
  const secondLanguage = getLanguageFromList(languageB, languages);
  const translationSelection = translationSelections.find(
    (selection) => selection.id === translationSelectionId,
  ) || translationSelections[0];
  const provider = translationSelection.provider;
  const model = translationSelection.model || "gpt-5.6-luna";
  const geminiModel = translationSelection.geminiModel || "gemini-3.1-flash-lite";
  const busy = state !== "ready" && state !== "error";

  useEffect(() => {
    loadConfig().then(setConfig).catch(() => undefined);
  }, []);

  useEffect(() => {
    localStorage.setItem("between.translation", translationSelectionId);
    localStorage.setItem("between.tts", ttsSelection);
  }, [translationSelectionId, ttsSelection]);

  useEffect(() => {
    if (state !== "listening") return;
    const startedAt = Date.now();
    setRecordingSeconds(0);
    const interval = window.setInterval(
      () => setRecordingSeconds(Math.floor((Date.now() - startedAt) / 1000)),
      250,
    );
    return () => window.clearInterval(interval);
  }, [state]);

  useEffect(
    () => () => {
      recorder.current.cancel().catch(() => undefined);
      ttsProviders.current.browser.stop();
      ttsProviders.current.gemini.stop();
    },
    [],
  );

  const clearTurn = () => {
    setTextA("");
    setTextB("");
    setResult(undefined);
    setResultSide(undefined);
    setError(undefined);
  };

  const startListening = async (side: LanguageSide) => {
    const currentOperation = ++operationId.current;
    try {
      activeTts.current?.stop();
      clearTurn();
      await recorder.current.start();
      if (currentOperation !== operationId.current) {
        await recorder.current.cancel();
        return;
      }
      setRecordingSide(side);
      setState("listening");
    } catch (caught) {
      if (currentOperation !== operationId.current) return;
      setRecordingSide(undefined);
      setError(toMessage(caught));
      setState("error");
    }
  };

  const stopAndProcess = async (side: LanguageSide) => {
    const currentOperation = operationId.current;
    const sourceLanguage = side === "a" ? firstLanguage : secondLanguage;
    const targetLanguage = side === "a" ? secondLanguage : firstLanguage;
    setState("recognizing");

    try {
      const stoppedAt = performance.now();
      const wav = await recorder.current.stop();
      const recordingFinalizeMs = performance.now() - stoppedAt;
      if (currentOperation !== operationId.current) return;

      const recognitionStartedAt = performance.now();
      const transcript = await transcribe(wav, sourceLanguage.whisperCode);
      const recognitionRoundTripMs = performance.now() - recognitionStartedAt;
      if (currentOperation !== operationId.current) return;
      if (side === "a") setTextA(transcript.text);
      else setTextB(transcript.text);

      setState("translating");
      await nextPaint();
      const translationStartedAt = performance.now();
      const translated = await translate({
        provider,
        languageA,
        languageB,
        sourceLanguage: sourceLanguage.code,
        text: transcript.text,
        model,
        geminiModel,
      });
      const translationRoundTripMs = performance.now() - translationStartedAt;
      if (currentOperation !== operationId.current) return;
      const targetSide: LanguageSide = side === "a" ? "b" : "a";
      if (targetSide === "a") setTextA(translated.translatedText);
      else setTextB(translated.translatedText);
      setResult(translated);
      setResultSide(targetSide);

      setState("speaking");
      const playbackRequestedAt = performance.now();
      const tts = ttsProviders.current[ttsSelection];
      activeTts.current = tts;
      await tts.speak(translated.translatedText, targetLanguage, () => {
        console.info("[timing] playback-start", {
          startLatencyMs: roundMs(performance.now() - playbackRequestedAt),
          afterStopTapMs: roundMs(performance.now() - stoppedAt),
        });
      });
      if (currentOperation !== operationId.current) return;
      console.info("[timing] turn", {
        recordingFinalizeMs: roundMs(recordingFinalizeMs),
        recognitionRoundTripMs: roundMs(recognitionRoundTripMs),
        recognitionServerMs: transcript.processingMs,
        translationRoundTripMs: roundMs(translationRoundTripMs),
        translationServerMs: translated.processingMs,
        playbackTotalMs: roundMs(performance.now() - playbackRequestedAt),
        provider,
        model,
        geminiModel,
      });
      setRecordingSide(undefined);
      setState("ready");
    } catch (caught) {
      if (currentOperation !== operationId.current) return;
      setRecordingSide(undefined);
      setError(toMessage(caught));
      setState("error");
    }
  };

  const handleVoiceAction = (side: LanguageSide) => {
    if (state === "listening" && recordingSide === side) {
      void stopAndProcess(side);
      return;
    }
    if (!busy) void startListening(side);
  };

  const replay = async (side: LanguageSide) => {
    if (!result || resultSide !== side || busy) return;
    const currentOperation = ++operationId.current;
    const language = side === "a" ? firstLanguage : secondLanguage;
    try {
      setError(undefined);
      setState("speaking");
      const tts = ttsProviders.current[ttsSelection];
      activeTts.current = tts;
      await tts.speak(result.translatedText, language);
      if (currentOperation !== operationId.current) return;
      setState("ready");
    } catch (caught) {
      if (currentOperation !== operationId.current) return;
      setError(toMessage(caught));
      setState("error");
    }
  };

  const stopPlayback = () => {
    if (state !== "speaking") return;
    operationId.current += 1;
    activeTts.current?.stop();
    setRecordingSide(undefined);
    setState("ready");
  };

  const updateLanguage = (side: LanguageSide, value: string) => {
    if (side === "a") setLanguageA(value);
    else setLanguageB(value);
    clearTurn();
    setState("ready");
  };

  return (
    <div className="app-shell">
      <main className="minimal-workspace">
        <header className="minimal-header">
          <h1>Say it<span>.</span></h1>
          <button
            className="settings-button"
            onClick={() => setSettingsOpen(true)}
            aria-label="Open settings"
          >
            <SettingsIcon />
          </button>
        </header>

        <section className="language-section" aria-label="Languages">
          <div className="language-row">
            <div className="language-control-group">
              <LanguageSelect
                side="a"
                value={languageA}
                otherValue={languageB}
                languages={languages}
                disabled={busy}
                onChange={(value) => updateLanguage("a", value)}
              />
              <VoiceButton
                side="a"
                language={firstLanguage}
                state={state}
                active={recordingSide === "a"}
                disabled={busy && (state !== "listening" || recordingSide !== "a")}
                onClick={() => handleVoiceAction("a")}
              />
            </div>
            <div className="language-control-group">
              <LanguageSelect
                side="b"
                value={languageB}
                otherValue={languageA}
                languages={languages}
                disabled={busy}
                onChange={(value) => updateLanguage("b", value)}
              />
              <VoiceButton
                side="b"
                language={secondLanguage}
                state={state}
                active={recordingSide === "b"}
                disabled={busy && (state !== "listening" || recordingSide !== "b")}
                onClick={() => handleVoiceAction("b")}
              />
            </div>
          </div>
        </section>

        <TurnProgress
          state={state}
          hasResult={Boolean(result)}
          recordingSeconds={recordingSeconds}
          translationLabel={translationSelection.label}
          ttsLabel={ttsSelection === "gemini" ? "Gemini Flash" : "Browser TTS"}
          onStopPlayback={stopPlayback}
        />

        <section className="phrase-grid" aria-label="Translation">
          <PhraseCard
            side="a"
            text={textA}
            isResult={resultSide === "a"}
            disabled={busy}
            onReplay={() => void replay("a")}
          />
          <PhraseCard
            side="b"
            text={textB}
            isResult={resultSide === "b"}
            disabled={busy}
            onReplay={() => void replay("b")}
          />
        </section>

        {error && <div className="error-message" role="alert">{error}</div>}
      </main>

      {settingsOpen && (
        <SettingsPanel
          selectedTranslation={translationSelectionId}
          onSelectTranslation={setTranslationSelectionId}
          selectedTts={ttsSelection}
          onSelectTts={(selection) => {
            activeTts.current?.stop();
            setTtsSelection(selection);
          }}
          onClose={() => setSettingsOpen(false)}
        />
      )}
    </div>
  );
}

function TurnProgress({
  state,
  hasResult,
  recordingSeconds,
  translationLabel,
  ttsLabel,
  onStopPlayback,
}: {
  state: VoiceTranslatorState;
  hasResult: boolean;
  recordingSeconds: number;
  translationLabel: string;
  ttsLabel: string;
  onStopPlayback: () => void;
}) {
  const activeIndex = state === "listening" || state === "recognizing"
    ? 0
    : state === "translating"
      ? 1
      : state === "speaking"
        ? 2
        : state === "ready" && hasResult
          ? 3
          : -1;
  const stages = [
    "Speech recognition (Groq Whisper)",
    `Translation (${translationLabel})`,
    `Playback (${ttsLabel})`,
  ];

  return (
    <section className="progress-panel" aria-label="Turn progress">
      <div className="progress-inline">
        <div className="progress-track">
          {stages.map((label, index) => {
            const completed = activeIndex === 3 || index < activeIndex;
            const active = index === activeIndex;
            return (
              <div
                className={`progress-stage ${completed ? "completed" : ""} ${active ? "active" : ""}`}
                key={label}
                aria-current={active ? "step" : undefined}
              >
                <span className="progress-dot" />
                <span>{label}</span>
              </div>
            );
          })}
        </div>
        <div className="progress-inline-action" aria-live="polite">
          {state === "listening" && (
            <span className="recording-timer">{formatClock(recordingSeconds)}</span>
          )}
          {state === "speaking" && (
            <button
              className="inline-stop-button"
              type="button"
              onClick={onStopPlayback}
              aria-label="Stop playback"
            >
              <StopIcon />
            </button>
          )}
        </div>
      </div>
    </section>
  );
}

function LanguageSelect({
  side,
  value,
  otherValue,
  languages,
  disabled,
  onChange,
}: {
  side: LanguageSide;
  value: string;
  otherValue: string;
  languages: Language[];
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const language = getLanguageFromList(value, languages);

  return (
    <label className={`language-select language-${side}`}>
      <span aria-hidden="true">{language.shortLabel}</span>
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        aria-label={side === "a" ? "First language" : "Second language"}
      >
        {languages.map((item) => (
          <option key={item.code} value={item.code} disabled={item.code === otherValue}>
            {item.nativeName}
          </option>
        ))}
      </select>
    </label>
  );
}

function VoiceButton({
  side,
  language,
  state,
  active,
  disabled,
  onClick,
}: {
  side: LanguageSide;
  language: Language;
  state: VoiceTranslatorState;
  active: boolean;
  disabled: boolean;
  onClick: () => void;
}) {
  const processing = active && state !== "listening";
  const label = active && state === "listening" ? language.stopLabel : language.speakLabel;

  return (
    <button
      className={`voice-button voice-${side} ${active ? "recording" : ""}`}
      onClick={onClick}
      disabled={disabled}
      aria-label={`${label} — ${language.nativeName}`}
    >
      <span className="voice-icon">
        {active && state === "listening" ? (
          <StopIcon />
        ) : processing ? (
          <span className="spinner" />
        ) : (
          <MicIcon />
        )}
      </span>
      <span>{label}</span>
    </button>
  );
}

function PhraseCard({
  side,
  text,
  isResult,
  disabled,
  onReplay,
}: {
  side: LanguageSide;
  text: string;
  isResult: boolean;
  disabled: boolean;
  onReplay: () => void;
}) {
  return (
    <article className={`phrase-card phrase-${side}`}>
      {text && <p>{text}</p>}
      {isResult && (
        <button
          className="replay-button"
          onClick={onReplay}
          disabled={disabled}
          aria-label="Replay translation"
        >
          <PlayIcon />
        </button>
      )}
    </article>
  );
}

function SettingsPanel({
  selectedTranslation,
  onSelectTranslation,
  selectedTts,
  onSelectTts,
  onClose,
}: {
  selectedTranslation: TranslationSelectionId;
  onSelectTranslation: (selection: TranslationSelectionId) => void;
  selectedTts: TtsSelectionId;
  onSelectTts: (selection: TtsSelectionId) => void;
  onClose: () => void;
}) {
  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <aside className="settings-drawer" onMouseDown={(event) => event.stopPropagation()}>
        <div className="drawer-heading">
          <h2>Settings</h2>
          <button className="close-button" onClick={onClose} aria-label="Close settings">
            <CloseIcon />
          </button>
        </div>

        <div className="settings-fields">
          <label>
            <span>Speech recognition</span>
            <select
              value="groq-whisper"
              onChange={() => undefined}
              aria-label="Speech recognition"
            >
              <option value="groq-whisper">Groq Whisper</option>
            </select>
          </label>
          <label>
            <span>Translation</span>
            <select
              value={selectedTranslation}
              onChange={(event) =>
                onSelectTranslation(event.target.value as TranslationSelectionId)
              }
            >
              {translationSelections.map((selection) => (
                <option key={selection.id} value={selection.id}>{selection.label}</option>
              ))}
            </select>
          </label>
          <label>
            <span>Speech playback</span>
            <select
              value={selectedTts}
              onChange={(event) => onSelectTts(event.target.value as TtsSelectionId)}
              aria-label="Speech playback"
            >
              <option value="browser">Browser TTS</option>
              <option value="gemini">Gemini Flash</option>
            </select>
          </label>
        </div>
      </aside>
    </div>
  );
}

function getLanguageFromList(code: string, languages: Language[]): Language {
  return languages.find((language) => language.code === code) || languages[0];
}

function formatClock(seconds: number): string {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, "0");
  const remainder = (seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}

function nextPaint(): Promise<void> {
  return new Promise((resolve) => {
    requestAnimationFrame(() => window.setTimeout(resolve, 0));
  });
}

function roundMs(value: number): number {
  return Math.round(value * 10) / 10;
}
