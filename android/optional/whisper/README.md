# On-device Whisper build source

This folder keeps the pinned Android `whisper.cpp` source module used to build
`app/libs/whisperlib-release.aar`. The application includes that arm64 runtime,
but model weights are deliberately excluded from both Git and the APK.

The active app provider is `app/src/main/java/com/sayit/translator/WhisperSttProvider.kt`.
It loads `ggml-large-v3-turbo-q4_0.bin` from app-private storage after the user downloads
the model in Settings. Delivery metadata, checksum and the GitHub Release URL
live in `app/src/main/assets/whisper_models.json`.

The provider is batch-based: it publishes the final transcript after `stop()`
in regular mode. `Whisper Offline Live` additionally decodes one rolling audio
six-second window every 1500 ms and publishes mutable greedy partial text while
retaining the complete recording for a separate beam-search final decode. The
runtime is preloaded before either offline mode starts and stays warm between
utterances. Pressing Stop sends a native abort signal to any in-flight partial
pass, waits 200 ms for trailing speech, and then starts the final decode without
waiting for that partial pass to finish normally. A bundled Silero VAD v6.2.0
pass trims only leading and trailing silence; uncertain or insignificant trims
fall back to the original recording.

Partial inference uses greedy decoding without timestamps. Final inference uses
beam size 3, also without timestamps, and disables temperature fallback passes.
After each native pass the runtime exposes mel, sampling, encoder, decoder,
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
