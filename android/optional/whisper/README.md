# On-device Whisper build source

This folder keeps the pinned Android `whisper.cpp` source module used to build
`app/libs/whisperlib-release.aar`. The application includes that arm64 runtime,
but model weights are deliberately excluded from both Git and the APK.

The active app provider is `app/src/main/java/com/sayit/translator/WhisperSttProvider.kt`.
It loads `ggml-large-v3-turbo-q4_0.bin` from app-private storage after the model
is downloaded in Settings. Delivery metadata, checksums and the GitHub Release URL live in
`app/src/main/assets/whisper_models.json`.

The provider is batch-based and publishes the final transcript after `stop()`.
The runtime is preloaded before recording and stays warm between utterances.
Pressing Stop waits 200 ms for trailing speech and then starts the final decode.
A bundled Silero VAD v6.2.0
pass trims only leading and trailing silence; uncertain or insignificant trims
fall back to the original recording.

Final inference uses beam size 3 without timestamps and disables temperature
fallback passes. After each native pass the runtime exposes mel, sampling, encoder, decoder,
batched-decoder and prompt timings plus run/fallback counters to the application;
these values are included in the exported last-cycle diagnostics.

From the `android` directory, rebuild the checked-in runtime after changing
Kotlin or JNI code:

```bash
git -C ../references/whisper.cpp apply \
  ../../android/optional/whisper/patches/detailed-timings.patch
./gradlew -p optional/whisper :whisperlib:assembleRelease
cp optional/whisper/whisperlib/build/outputs/aar/whisperlib-release.aar \
  app/libs/whisperlib-release.aar
```

The patch is already applied in the local development checkout used for this
build. Apply it only once to a clean source checkout pinned to the documented
commit; `git apply --check` can be used before applying it.
