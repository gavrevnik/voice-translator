# Native translation runtime

`translate-kit-android-0.1.0-arm64.aar` is built from
[`marcosholgado/translate-kit`](https://github.com/marcosholgado/translate-kit)
commit `2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4` (tag `0.1.0`).

The AAR contains only the `arm64-v8a` JNI library and no model weights. Rebuild
it with `android/tools/build_translate_kit_aar.sh`. The app therefore targets
modern arm64 Android phones and requires API 28 or newer.

Licensing and third-party notices are in this directory.

`whisperlib-release.aar` is an arm64-only `whisper.cpp` runtime built from commit
`da54572229bcf64ba367d96c7ef15770376c4280`. It contains native inference
libraries but no model weights. The multilingual `small-q5_1` model is downloaded
separately after the user selects Whisper Offline. `whisper.cpp` is MIT licensed;
the license text is in [`LICENSE.whisper.cpp`](LICENSE.whisper.cpp).

Rebuild the runtime, including the Live partial/final decode JNI options, from
the `android` directory:

```bash
./gradlew -p optional/whisper :whisperlib:assembleRelease
cp optional/whisper/whisperlib/build/outputs/aar/whisperlib-release.aar \
  app/libs/whisperlib-release.aar
```
