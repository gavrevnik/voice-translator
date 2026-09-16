# On-device Whisper build source

This folder keeps the pinned Android `whisper.cpp` source module used to build
`app/libs/whisperlib-release.aar`. The application includes that arm64 runtime,
but model weights are deliberately excluded from both Git and the APK.

The active app provider is `app/src/main/java/com/sayit/translator/WhisperSttProvider.kt`.
It loads `ggml-small-q5_1.bin` from app-private storage after the user downloads
the model in Settings. Delivery metadata, checksum and the GitHub Release URL
live in `app/src/main/assets/whisper_models.json`.

The provider is batch-based: it publishes the final transcript after `stop()`
in regular mode. `Whisper Offline Live` additionally decodes one rolling audio
window at a time and publishes mutable partial text while retaining the complete
recording for a separate high-accuracy final decode.

From the `android` directory, rebuild the checked-in runtime after changing
Kotlin or JNI code:

```bash
./gradlew -p optional/whisper :whisperlib:assembleRelease
cp optional/whisper/whisperlib/build/outputs/aar/whisperlib-release.aar \
  app/libs/whisperlib-release.aar
```
