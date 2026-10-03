# Optional offline/mobile engines

This directory preserves the Android offline translation and transcription implementation without making it part of the application source set or release APK.

## Preserved files

- `android-src/`: OPUS/translate-kit and Whisper providers plus model managers.
- `assets/`: the former OPUS and Whisper download manifests. The Silero VAD model is retained locally but ignored by Git; supply it separately when restoring an engine that needs it.
- `libs/`: the former translate-kit and whisperlib AARs, notices, and license files.
- `../whisper/`: the reproducible whisper.cpp Android module and local patches.
- `../groq/android-src/`: the former Groq Whisper connector.
- `../android-stt/android-src/`: the former Android SpeechRecognizer provider.

None of these paths is referenced by `app/build.gradle.kts`. Do not add `optional/` as a broad source directory: that would silently package every dormant engine.

## Re-enabling an engine

Re-enable only the selected engine and treat it as an explicit product change:

1. Copy its Kotlin sources into `app/src/main/java/com/sayit/translator/` and only its required assets into `app/src/main/assets/`.
2. For OPUS or Whisper, restore the exact AAR dependency from `libs/` in `app/build.gradle.kts`; for downloaded models, restore the corresponding BuildConfig URL override and manifest loading.
3. Restore the relevant `AppModels`, `TranslatorUiState`, `AppSettings`, `TranslatorViewModel`, and Settings UI wiring from Git history. Optional providers use `AppLanguage.code` for the six Whisper language codes.
4. Restore engine-specific JVM tests and device scenarios. Do not replace Gemini VAD/RMS behavior accidentally.
5. Run `testDebugUnitTest lintDebug assembleRelease`, inspect `zipinfo -1` and DEX/native contents, and confirm only the intended runtime and assets were added.
6. Recheck all third-party notices and redistribution terms before publishing an APK.

The preserved Groq file still contains its legacy standalone PCM helper implementation. If Groq is restored alongside the active `PcmAudioRecorder.kt`, retain only the connector section and reuse the active recorder to avoid duplicate declarations.
