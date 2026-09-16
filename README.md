# Voice Translator

Двусторонний голосовой переводчик с веб-клиентом и нативным Android-приложением. Пользователь выбирает пару языков и нажимает микрофон на стороне языка, на котором говорит; приложение распознаёт речь, переводит текст в противоположную сторону и автоматически озвучивает результат.

Поддерживаются сербский, хорватский, английский, румынский, русский и испанский языки.

## Схема и доступные движки

Каждый голосовой запрос проходит три этапа: `STT → Translate → TTS`.

На Android однострочная шкала показывает сокращённые имена реально выбранных движков, например `Android → Gemini 3.1 → Android`: справа во время записи появляется таймер, а во время озвучивания — компактная квадратная кнопка остановки. Конкретные провайдеры и модели выбираются на отдельном экране настроек.

| Этап | Web | Android |
| --- | --- | --- |
| **STT** — речь → текст | **Browser STT** (`SpeechRecognition`) по умолчанию или **Groq Whisper Large V3** (`whisper-large-v3`) | **Android/Samsung SpeechRecognizer** по умолчанию, **Groq Whisper Large V3** (`whisper-large-v3`) или локальный multilingual **Whisper Offline** (`base-q5_1`) для всех языков приложения |
| **Translate** — перевод | **Gemini Flash 3.1** по умолчанию (`gemini-3.1-flash-lite`, thinking `minimal`), **Gemini Flash 3.5** (`gemini-3.5-flash-lite`) или **Codex SDK** (`gpt-5.6-luna`, reasoning `low`) | **Gemini Flash 3.1** по умолчанию (`gemini-3.1-flash-lite`, thinking `minimal`), **Gemini Flash 3.5** (`gemini-3.5-flash-lite`), **OpenAI API** (`gpt-5.6-luna`, reasoning `none`) или локальный **OPUS Slavic — Offline** для `Russian ↔ Serbian/Croatian` |
| **TTS** — текст → речь | **Browser TTS** по умолчанию или **Gemini 3.1 Flash TTS Preview** (`gemini-3.1-flash-tts-preview`) | Только **Android System TTS** |

Выбор модели перевода действует до перезапуска: при обновлении веб-страницы или новом запуске Android-приложения снова выбирается Gemini Flash 3.1. Остальные пользовательские настройки сохраняются.

На вебе распознавание по умолчанию выполняет встроенный браузерный `SpeechRecognition`. Если в настройках выбран Groq Whisper, WAV-запись отправляется с локального Node-бэкенда в Groq. Gemini TTS возвращается браузеру как WAV. Ключи Gemini и Groq не передаются браузерному JavaScript. В Android облачные ключи встраиваются в тестовый APK, а OpenAI-ключ вводится в приложении и хранится через Android Keystore.

Офлайн-перевод доступен только на arm64-устройствах с Android 9+ и для пар русский ↔ сербский/хорватский. Для сербского можно выбрать латиницу (по умолчанию) или кириллицу. INT8-веса не входят в APK: приложение скачивает отдельный проверяемый пакет размером **45,0 MB** (**66,8 MB** после установки) и сохраняет его между обновлениями APK. Отдельная multilingual-модель Whisper Offline также не входит в APK: `base-q5_1` занимает **59,7 MB**, скачивается из GitHub Releases и работает для всех языков приложения.

## Быстрый запуск веб-версии

Нужны Node.js 20+ и заполненный `GEMINI_API_KEY` в корневом `.env` для выбранного по умолчанию Gemini Flash 3.1. `GROQ_API_KEY` нужен только при выборе Groq Whisper, а локальная авторизация Codex — только при выборе Codex SDK.

Самый простой способ на macOS — дважды нажать [`Say it.app`](Say%20it.app). Нативный ярлык работает без окна Terminal: при необходимости установит npm-зависимости, в фоне запустит Node API и Vite, затем откроет [http://127.0.0.1:5173](http://127.0.0.1:5173). Локальные логи и PID хранятся в игнорируемой папке `.runtime/`.

Ручной запуск:

```bash
npm install
cp .env.example .env  # только если .env ещё не создан
npm run dev
```

Gemini Flash 3.1 выбран по умолчанию; в настройках можно переключиться на Flash 3.5 или Codex SDK. Настройки Codex можно переопределить переменными из [`.env.example`](.env.example).

## Конфигурация и секреты

Единственный локальный файл секретов — `.env`; он исключён из Git. Репозиторий содержит только безопасный шаблон [`.env.example`](.env.example):

- `GROQ_API_KEY` — опциональный Groq STT на вебе и Android;
- `GEMINI_API_KEY` — Gemini-перевод в вебе/Android и Gemini TTS в вебе;
- `CODEX_CLI_PATH` — необязательный путь к Codex CLI для веб-провайдера;
- `OFFLINE_OPUS_MODEL_URL` — необязательная замена URL Android model pack для тестового CDN/локального сервера;
- `OFFLINE_WHISPER_MODEL_URL` — необязательная замена URL Android Whisper `base-q5_1` model pack;
- `PORT` — порт локального API, по умолчанию `8787`.

Android-сборка читает Gemini/Groq ключи из корневого `.env` и встраивает их в APK. Такой APK предназначен только для личного тестирования: ключи можно извлечь. Перед распространением приложения перенесите облачные вызовы на собственный backend и отзовите встроенные ключи.

## Android

Подробности находятся в [`android/README.md`](android/README.md). Для сборки нужны JDK 17 и Android SDK 35:

```bash
cd android
./gradlew testReleaseUnitTest assembleRelease
```

Release APK создаётся в `android/app/build/outputs/apk/release/`. APK/AAB, `.env`, `references/`, зависимости, модели и build-каталоги исключены из Git.

## Команды

```bash
npm run dev           # Node API + Vite в режиме разработки
npm run check         # unit-тесты, TypeScript и production build
npm run start         # production-сервер на 127.0.0.1:8787
```

## Структура проекта

- `src/` — React-интерфейс, Browser STT/TTS, запись звука для Groq и клиент API.
- `server/stt/` — серверный адаптер Groq Whisper.
- `server/translation/` — изолированные провайдеры Codex SDK и Gemini.
- `server/tts/` — серверный адаптер Gemini TTS для веб-клиента.
- `android/app/` — активное Jetpack Compose приложение.
- `android/tools/` — воспроизводимая INT8-конвертация OPUS, квантизация Whisper и сборка JNI runtime.
- `android/optional/whisper/` — исходный Android-модуль `whisper.cpp`, из которого собран подключённый к APK arm64 runtime; веса доставляются отдельно.

Веб и Android используют одну и ту же системную инструкцию с каноническими именами и кодами выбранных языков. Адаптеры передают её как `developer_instructions` в Codex SDK, `instructions` в OpenAI Responses API и `systemInstruction` в Gemini; распознанный текст всегда отправляется отдельно как недоверенный user input. Ответ переводчика проверяется по минимальной JSON-схеме. Замеры этапов доступны как `[timing]` в веб-консоли/Node и через Android-тег `SayItTiming`.
