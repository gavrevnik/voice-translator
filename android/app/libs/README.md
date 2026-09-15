# Native translation runtime

`translate-kit-android-0.1.0-arm64.aar` is built from
[`marcosholgado/translate-kit`](https://github.com/marcosholgado/translate-kit)
commit `2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4` (tag `0.1.0`).

The AAR contains only the `arm64-v8a` JNI library and no model weights. Rebuild
it with `android/tools/build_translate_kit_aar.sh`. The app therefore targets
modern arm64 Android phones and requires API 28 or newer.

Licensing and third-party notices are in this directory.
