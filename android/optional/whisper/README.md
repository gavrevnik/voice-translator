# On-device Whisper build source

This folder keeps the pinned Android `whisper.cpp` source module used to build
`app/libs/whisperlib-release.aar`. The application includes that arm64 runtime,
but model weights are deliberately excluded from both Git and the APK.

The active app provider is `app/src/main/java/com/sayit/translator/WhisperSttProvider.kt`.
It loads `ggml-base-q5_1.bin` from app-private storage after the user downloads
the model in Settings. Delivery metadata, checksum and the GitHub Release URL
live in `app/src/main/assets/whisper_models.json`.

The provider is batch-based: it publishes the final transcript after `stop()`
and does not emit live partial words without a streaming/chunked redesign.
