# Voice Translator repository guide

When this checkout is inside `life-stack`, first read the shared [workspace instructions](../AGENTS.md) if they have not already been loaded. This file supplies project-specific overrides. If the shared file is absent in a standalone clone, continue with this file.

This file applies to the entire repository. Start with the root [README](README.md). For Android implementation details, model manifests, build commands, and current limitations, read [android/README.md](android/README.md).

## Default scope

- Unless the user explicitly asks for web changes, change only the native Android application under `android/` and Android-related documentation.
- Do not modify `src/`, `server/`, web dependencies, or web behavior merely to keep the two clients visually or functionally aligned.
- Preserve unrelated local changes. Multiple tasks may be working in the same branch, so inspect `git status` and overlapping diffs before editing or committing.

## Project map

- `android/app/src/main/java/com/sayit/translator/TranslatorScreen.kt`: Jetpack Compose UI for Conversation mode and Settings.
- `android/app/src/main/java/com/sayit/translator/TranslatorViewModel.kt`: voice-cycle orchestration and UI state transitions.
- `android/app/src/main/java/com/sayit/translator/AppModels.kt`: languages, engines, state, defaults, and shared validation helpers.
- `android/app/src/main/java/com/sayit/translator/AppSettings.kt`: persisted Android settings and migrations.
- `android/app/src/main/java/com/sayit/translator/GeminiLiveTranscribeSttProvider.kt` and `SttProvider.kt`: the active Gemini-only recognition path and shared STT contract.
- `android/app/src/main/java/com/sayit/translator/GeminiTranslationProvider.kt`: the active Gemini-only translation path.
- `android/app/src/main/java/com/sayit/translator/PcmAudioRecorder.kt`: PCM capture plus the 1-second local RMS safety stop shared by Gemini Live.
- `android/app/src/main/java/com/sayit/translator/AppIcons.kt`: the small local vector subset replacing `material-icons-extended`.
- `android/app/src/main/java/com/sayit/translator/TtsProvider.kt` and `AndroidLanguagePacks.kt`: Android TTS selection and Android speech-package discovery/install flows.
- `android/optional/offline/`: dormant OPUS/translate-kit and Whisper source, manifests, AARs, licenses, and restoration notes. Nothing under `android/optional/` belongs to the main source set unless a user explicitly asks to restore an engine.
- `android/optional/groq/` and `android/optional/android-stt/`: dormant Groq and system SpeechRecognizer providers.
- `android/app/src/test/`: JVM tests for shared logic and audio/pause behavior.
- `android/tools/` and `android/optional/whisper/`: preserved model/runtime build tooling. Prefer these scripts over ad-hoc conversions when an offline engine is explicitly restored.
- `src/` and `server/`: React/Node web client and backend. They are outside the default task scope.

## Android implementation rules

- The supported app languages are Serbian, Croatian, English, Romanian, Russian, and Spanish.
- The active voice pipeline is Gemini 3.5 Transcribe Live → Gemini Flash 3.1/3.5 → Android TTS. Do not expose dormant Whisper, Groq, Android STT, or OPUS choices without an explicit product request.
- Keep user-facing settings compatible across upgrades. When a preference type or enum changes, migrate or safely fall back from previously stored values.
- Offline model weights do not belong in Git or the application APK. Dormant runtime libraries, manifests, and licenses stay under `android/optional/`, outside Gradle source sets and dependencies.
- Keep `material-icons-extended` out of dependencies. Add an individual local vector to `AppIcons.kt` when a non-core icon is needed.
- API keys from the root `.env` are embedded in local APKs. Never print, commit, or include them in diagnostics.
- Update `android/README.md` whenever behavior, defaults, supported engines, model delivery, or build steps change. Update the root README when the high-level feature description changes.

## Testing and APK handoff

- Use JDK 17. On the current Codex host, Homebrew OpenJDK 17 is installed at `/opt/homebrew/opt/openjdk@17`, but it is not registered with macOS `/usr/bin/java`. Do not treat the `/usr/bin/java` error as a missing JDK and do not change global Java settings; pass its home directly to Gradle. If the Homebrew prefix changes, resolve it with `brew --prefix openjdk@17` and append `/libexec/openjdk.jdk/Contents/Home`.
- From `android/`, run at minimum:

  ```bash
  env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest lintDebug assembleRelease
  ```

- Gradle uses the user cache under `~/.gradle` and may need network access when dependencies are missing. In a sandboxed Codex run, request the required permission instead of interpreting cache-access errors as build failures.

- For release-facing changes, also inspect `app/build/reports/lint-results-debug.html`, verify `versionCode`/`versionName`, and check APK metadata with Android `aapt dump badging` when available.
- Test pure state, parsing, range, and audio-boundary logic with JVM tests. Add regression coverage for bugs that can be reproduced without a device.
- For UI or speech-provider changes, use a real Android device when available: install with `adb install -r`, exercise Conversation mode, manual Translate during LIVE, LIVE cancellation without translation, Gemini VAD/RMS fallback, replay/stop playback, language swapping, keep-screen-on, and missing/installed TTS voice states. Use `adb logcat -s SayItTiming` for the speech pipeline.
- A successful compile alone is not sufficient: report which tests ran, whether lint passed, the APK version, and any device-only behavior that could not be exercised.
- After successful testing, always return a clickable link to the final mobile APK at `android/app/build/outputs/apk/release/app-release.apk` (use its absolute local path in the final response).

## Optional model artifacts

- The active APK has no downloadable STT/translation model catalog. If an optional offline engine is restored, treat its asset name, tag, byte size, and SHA-256 manifest as one atomic contract.
- Prefer original, stable upstream downloads when redistribution is unnecessary and licenses permit it. Use this repository's GitHub Releases for app-specific model packs or pinned artifacts that the app must download reliably.
- Before using an existing release, inspect it with `gh release view <tag>` and confirm the exact asset name. After uploading, verify the public download URL and checksum rather than assuming the upload succeeded.
- Do not replace or delete release assets or tags unless the user explicitly authorizes that external change. Prefer a new versioned tag when an artifact's contents change.
- Never commit generated APKs, large model archives, downloaded weights, signing secrets, or `.env`. Build outputs remain local or are attached to a release only when requested.
