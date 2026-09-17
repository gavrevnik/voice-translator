# Voice Translator repository guide

This file applies to the entire repository. Start with the root [README](README.md). For Android implementation details, model manifests, build commands, and current limitations, read [android/README.md](android/README.md).

## Default scope

- Unless the user explicitly asks for web changes, change only the native Android application under `android/` and Android-related documentation.
- Do not modify `src/`, `server/`, web dependencies, or web behavior merely to keep the two clients visually or functionally aligned.
- Preserve unrelated local changes. Multiple tasks may be working in the same branch, so inspect `git status` and overlapping diffs before editing or committing.

## Project map

- `android/app/src/main/java/com/sayit/translator/TranslatorScreen.kt`: Jetpack Compose UI for Single mode, Conversation mode, and Settings.
- `android/app/src/main/java/com/sayit/translator/TranslatorViewModel.kt`: voice-cycle orchestration and UI state transitions.
- `android/app/src/main/java/com/sayit/translator/AppModels.kt`: languages, engines, state, defaults, and shared validation helpers.
- `android/app/src/main/java/com/sayit/translator/AppSettings.kt`: persisted Android settings and migrations.
- `android/app/src/main/java/com/sayit/translator/SttProviders.kt`, `GroqWhisperSttProvider.kt`, and `WhisperSttProvider.kt`: Android, Groq, and offline Whisper recognition.
- `android/app/src/main/java/com/sayit/translator/GeminiTranslationProvider.kt` and `OfflineOpusTranslationProvider.kt`: online and offline translation.
- `android/app/src/main/java/com/sayit/translator/TtsProvider.kt` and `AndroidLanguagePacks.kt`: Android TTS selection and Android speech-package discovery/install flows.
- `android/app/src/main/assets/offline_models.json` and `whisper_models.json`: downloadable model metadata; URLs, byte sizes, hashes, and required files must stay consistent with release assets.
- `android/app/src/test/`: JVM tests for shared logic and audio/pause behavior.
- `android/tools/` and `android/optional/whisper/`: reproducible model/runtime build tooling. Prefer these scripts over ad-hoc conversions.
- `src/` and `server/`: React/Node web client and backend. They are outside the default task scope.

## Android implementation rules

- The supported app languages are Serbian, Croatian, English, Romanian, Russian, and Spanish.
- The voice pipeline is STT → translation → Android TTS. Defaults are Groq Whisper, Gemini Flash 3.5, and Android Speech.
- Keep user-facing settings compatible across upgrades. When a preference type or enum changes, migrate or safely fall back from previously stored values.
- Offline model weights do not belong in Git or the application APK. Keep only manifests, runtime libraries, licenses, and reproducible build scripts in the repository.
- API keys from the root `.env` are embedded in local APKs. Never print, commit, or include them in diagnostics.
- Update `android/README.md` whenever behavior, defaults, supported engines, model delivery, or build steps change. Update the root README when the high-level feature description changes.

## Testing and APK handoff

- The current Codex host does not have a Java runtime installed, so local Gradle tasks cannot be run here. Do not repeatedly retry Gradle or assume that JDK 17 is available. Choose an alternative validation plan: run source-level/static checks, inspect existing reports and APK metadata only when they can be tied to the exact source revision, and exercise an already matching APK on a connected Android device when available. Clearly report that this fallback is not equivalent to a fresh build. For release validation, use another environment with JDK 17 (for example Android Studio or configured CI), or ask the user before installing a runtime.
- Use JDK 17. From `android/`, run at minimum:

  ```bash
  ./gradlew testDebugUnitTest lintDebug assembleRelease
  ```

- For release-facing changes, also inspect `app/build/reports/lint-results-debug.html`, verify `versionCode`/`versionName`, and check APK metadata with Android `aapt dump badging` when available.
- Test pure state, parsing, range, and audio-boundary logic with JVM tests. Add regression coverage for bugs that can be reproduced without a device.
- For UI or speech-provider changes, use a real Android device when available: install with `adb install -r`, exercise both layout modes, manual Stop and Groq pause-stop, replay/stop playback, language swapping, and missing/installed STT/TTS package states. Use `adb logcat -s SayItTiming` for the speech pipeline.
- A successful compile alone is not sufficient: report which tests ran, whether lint passed, the APK version, and any device-only behavior that could not be exercised.
- After successful testing, always return a clickable link to the final mobile APK at `android/app/build/outputs/apk/release/app-release.apk` (use its absolute local path in the final response).

## GitHub Releases and downloadable models

- Treat the asset name, tag, byte size, and SHA-256 in each Android asset manifest as one atomic contract. Verify all of them before changing a URL or publishing a build.
- Prefer original, stable upstream downloads when redistribution is unnecessary and licenses permit it. Use this repository's GitHub Releases for app-specific model packs or pinned artifacts that the app must download reliably.
- Before using an existing release, inspect it with `gh release view <tag>` and confirm the exact asset name. After uploading, verify the public download URL and checksum rather than assuming the upload succeeded.
- Do not replace or delete release assets or tags unless the user explicitly authorizes that external change. Prefer a new versioned tag when an artifact's contents change.
- Never commit generated APKs, large model archives, downloaded weights, signing secrets, or `.env`. Build outputs remain local or are attached to a release only when requested.
