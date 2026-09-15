export type VoiceTranslatorState =
  | "ready"
  | "listening"
  | "recognizing"
  | "translating"
  | "speaking"
  | "error";

export const stateLabels: Record<VoiceTranslatorState, string> = {
  ready: "Ready",
  listening: "Listening",
  recognizing: "Recognizing",
  translating: "Translating",
  speaking: "Speaking",
  error: "Error",
};

export const processingStates: VoiceTranslatorState[] = [
  "recognizing",
  "translating",
  "speaking",
];
