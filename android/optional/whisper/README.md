# Optional on-device Whisper module

This folder is deliberately outside the Android app source set. The current APK contains only
Samsung/Android `SpeechRecognizer`; neither the 141 MB model nor the native Whisper libraries are
packaged.

To restore Whisper later:

1. Copy `WhisperSttProvider.kt` into
   `app/src/main/java/com/sayit/translator/`.
2. Copy `models/ggml-base.bin` into `app/src/main/assets/models/`.
3. Add the following to `settings.gradle.kts`:

   ```kotlin
   include(":whisperlib")
   project(":whisperlib").projectDir = file("optional/whisper/whisperlib")
   ```

4. Add `implementation(project(":whisperlib"))` to `app/build.gradle.kts`.
5. Instantiate `WhisperSttProvider` in `TranslatorViewModel` and add the desired engine selector.

The optional provider remains batch-based: it can publish the final transcript after `stop()`, but
does not emit live partial words without a streaming/chunked recognition redesign.
