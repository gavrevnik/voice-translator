# Say it — Android

Нативный Jetpack Compose клиент для двустороннего голосового перевода на Android 8+ (`minSdk 26`). Поддерживаются сербский, хорватский, английский, румынский, русский и испанский языки.

## Активная конфигурация

- Распознавание: только потоковый **Gemini 3.5 Transcribe Live** (`gemini-3.5-transcribe-live`).
- Перевод: **Gemini Flash 3.5** (`gemini-3.5-flash-lite`) по умолчанию или **Gemini Flash 3.1** (`gemini-3.1-flash-lite`), thinking `minimal`.
- Озвучивание: только **Android System TTS** с установленным локальным голосом.

Android-сборка читает только `GEMINI_API_KEY` из корневого `.env`. Ключ используется для транскрибации и перевода и встраивается в APK, поэтому локальную сборку нельзя безопасно распространять третьим лицам без backend-прокси и смены ключа.

Whisper, Groq STT, Android `SpeechRecognizer`, OPUS/`translate-kit`, их model managers, manifests, ссылки загрузки и нативные библиотеки отключены от app source set. В APK нет `libtranslate-kit.so`, `libwhisper.so`, `libggml.so`, Silero VAD и JSON-каталогов офлайн-моделей.

Зависимость `material-icons-extended` также удалена. Шесть отсутствующих в базовом наборе glyphs хранятся как локальные Compose-векторы в `AppIcons.kt`, поэтому библиотека со всем расширенным каталогом не разрешается и не попадает в сборку.

## Интерфейс и Live

Главный экран всегда работает в Conversation mode: верхняя половина развёрнута на 180° для собеседника напротив, нижняя ориентирована обычно. В центре равномерно расположены Settings, **Live** и перестановка сторон.

В обычном режиме кнопка речи запускает Gemini Transcribe Live для выбранного языка. В **Live** сервис получает оба выбранных BCP-47 языка-кандидата, передаёт PCM16/16 kHz по WebSocket и показывает interim-текст. Так как ответ не содержит надёжного кода стороны, направление выбирает локальный ML Kit-классификатор строго внутри выбранной пары; перевод выполняет выбранный Gemini Flash.

Основной автоматический stop — server VAD Gemini:

- тишина после речи: **700 мс**;
- prefix padding: **250 мс**;
- sensitivity окончания речи: `END_SENSITIVITY_HIGH`;
- локальный RMS fallback: **1 000 мс** непрерывной тишины.

Фоновая музыка может удерживать RMS выше порога, но основной stop приходит от server VAD. Кнопка Translate, появляющаяся вместо Swap во время прослушивания, вручную завершает текущую реплику и запускает перевод. Повторное нажатие **Live** останавливает весь режим без перевода незавершённой реплики.

После озвучивания Live автоматически начинает следующий цикл, чтобы не распознавать собственный TTS. Пока Live активен и экран приложения открыт, `keepScreenOn` предотвращает системное выключение дисплея; после выхода из Live обычный тайм-аут восстанавливается.

В Settings доступны выбор Gemini-модели перевода, Android TTS voices, параметры автоматической остановки Gemini и экспорт диагностики. Recognition selector, OPUS Serbian-script selector, offline model panels, Groq pause input и ссылки на Groq скрыты. Android TTS проверяет локальные голоса Samsung → Google → Piper. При отсутствии голоса Install открывает поддерживаемый системный установщик или настройки TTS.

## Опциональные компоненты в репозитории

Отключённый код сохранён вне `android/app/`, поэтому Gradle не видит его в основной сборке:

- `optional/offline/android-src/` — OPUS provider/model manager и Whisper provider/model manager;
- `optional/offline/assets/` — прежние manifests; Silero VAD сохранён только локально и исключён из Git;
- `optional/offline/libs/` — `translate-kit`/Whisper AAR, notices и лицензии;
- `optional/whisper/` — исходный модуль `whisper.cpp` и патчи для воспроизводимой сборки;
- `optional/groq/android-src/` — прежний Groq Whisper connector;
- `optional/android-stt/android-src/` — прежний Android `SpeechRecognizer` provider.

Подробная карта и порядок восстановления находятся в [`optional/offline/README.md`](optional/offline/README.md). Компоненты намеренно не подключаются через build flavor: случайная сборка обычного release не должна вернуть тяжёлые AAR/assets. Для повторного включения нужно явно вернуть выбранные source/assets в `app/src/main`, добавить соответствующие AAR dependencies и BuildConfig-поля, восстановить enum/state/UI wiring и тесты, затем проверить лицензионные notices и содержимое APK.

Инструменты подготовки прежних моделей остаются в `tools/`. Сами model weights по-прежнему не должны попадать в Git или APK.

## Сборка

Создайте корневой `.env` из шаблона и заполните `GEMINI_API_KEY`:

```bash
cp ../.env.example ../.env
```

На текущем macOS host JDK 17 нужно передавать Gradle напрямую:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testDebugUnitTest lintDebug assembleRelease
```

Release APK создаётся в `app/build/outputs/apk/release/app-release.apk`. Release включает R8/minification и resource shrinking. Build-каталоги, APK, model packs и `.env` исключены из Git.

## Использование и диагностика

1. Выберите два языка и Gemini Flash 3.1/3.5 в настройках.
2. Нажмите кнопку речи и говорите; server VAD или RMS fallback завершит реплику автоматически.
3. Повторное нажатие кнопки речи завершает реплику вручную и запускает перевод.
4. В Live используйте появившуюся кнопку Translate для ручного завершения текущей реплики; кнопку Live — для отмены режима без перевода незавершённого звука.
5. Replay повторяет последний перевод, Stop останавливает TTS.

Замеры пишутся с тегом `SayItTiming`:

```bash
adb logcat -s SayItTiming
```

Экспорт диагностики не содержит аудио и API-ключей. Изменения VAD, аудиозаписи, TTS и keep-screen-on следует дополнительно проверять на реальном Android-устройстве.
