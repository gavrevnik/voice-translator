# Latency baseline

Measured on 2026-09-15 on the local Apple M4 development machine. The web backend was warm and
the default translation configuration was Codex SDK + `gpt-5.6-luna` + `low` reasoning.

| Stage | Runs | Result |
| --- | ---: | --- |
| Codex SDK translation, 62 characters | 3 | 9,044.9 ms, 9,682.9 ms, 14,395.5 ms server time |

Translation took 9.0–14.4 seconds and was the dominant measured post-recording bottleneck in this
configuration. Web STT has since moved to Groq Whisper Large V3 and needs a new network-inclusive
baseline. The browser-to-local-server hop in the existing measurements was negligible.

## Instrumentation

- Web backend: `[timing] stt` and `[timing] translation` in the Node terminal.
- Web client: `[timing] playback-start` and `[timing] turn` in browser DevTools.
- Android: connect the phone and run `adb logcat -s SayItTiming`.

Android logs split out first partial transcript, final transcript after the stop tap, provider HTTP time,
translation time, TTS start time, and stop-tap-to-playback-start time. No device was connected during
this baseline, so System/Groq STT and mobile-network numbers still need a phone run.

## Optimization order

1. Benchmark Gemini 3.1 Flash-Lite against Codex SDK. The SDK remains the web default, but
   its agent/session overhead is currently the largest measured delay.
2. Keep Luna with `low` reasoning for the default quality/latency balance. If translation quality stays
   acceptable, evaluate `none` as an additional latency mode in a controlled test.
3. Stream translation output and start TTS by complete sentence instead of waiting for the full
   response. This mainly improves perceived latency and needs interruption-safe buffering.
4. Measure Groq Whisper Large V3 round-trip time on the target connection, then compare it with the
   translation and TTS stages before optimizing further.
5. Use System SpeechRecognizer partial results on Android (implemented) so recognized words become
   visible before the final result is available.

Relevant provider documentation:

- <https://developers.openai.com/api/docs/models/gpt-5.6-luna>
- <https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite>
- <https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite>
- <https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-tts-preview>
- <https://console.groq.com/docs/speech-to-text>
