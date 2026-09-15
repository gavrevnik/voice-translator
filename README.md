# Voice Translator

Двусторонний голосовой переводчик с веб-клиентом и нативным Android-приложением. Пользователь выбирает пару языков и нажимает микрофон на стороне языка, на котором говорит; приложение распознаёт речь, переводит текст в противоположную сторону и автоматически озвучивает результат.

Поддерживаются сербский, английский, румынский, русский и испанский языки.

## Схема и доступные движки

Каждый голосовой запрос проходит три этапа: `STT → Translate → TTS`.

На Android однострочная шкала показывает `Speech → выбранная модель → Playback`: справа во время записи появляется таймер, а во время озвучивания — компактная квадратная кнопка остановки. Конкретные провайдеры и модели выбираются на отдельном экране настроек.

| Этап | Web | Android |
| --- | --- | --- |
| **STT** — речь → текст | **Groq Whisper Large V3** (`whisper-large-v3`), единственный вариант | **Android/Samsung SpeechRecognizer** по умолчанию или **Groq Whisper Large V3** (`whisper-large-v3`) |
| **Translate** — перевод | **Codex SDK** по умолчанию (`gpt-5.6-luna`, фиксированный reasoning `low`) или **Gemini Flash-Lite** (`gemini-3.1-flash-lite` / `gemini-3.5-flash-lite`, thinking `minimal`) | **Gemini Flash-Lite** по умолчанию (`gemini-3.1-flash-lite` / `gemini-3.5-flash-lite`, thinking `minimal`) или **OpenAI API** (`gpt-5.6-luna`, reasoning `none`) |
| **TTS** — текст → речь | **Browser TTS** по умолчанию или **Gemini 3.1 Flash TTS Preview** (`gemini-3.1-flash-tts-preview`) | **Android System TTS** по умолчанию или **Gemini 3.1 Flash TTS Preview** (`gemini-3.1-flash-tts-preview`) |

На вебе WAV-запись отправляется с локального Node-бэкенда в Groq для распознавания, а Gemini TTS возвращается браузеру как WAV. Ключи Gemini и Groq не передаются браузерному JavaScript. В Android облачные ключи встраиваются в тестовый APK, а OpenAI-ключ вводится в приложении и хранится через Android Keystore.

## Быстрый запуск веб-версии

Нужны Node.js 20+, заполненный `GROQ_API_KEY` в корневом `.env` и локальная авторизация Codex для выбранного по умолчанию переводчика.

Самый простой способ на macOS — дважды нажать [`Say it.app`](Say%20it.app). Нативный ярлык работает без окна Terminal: при необходимости установит npm-зависимости, в фоне запустит Node API и Vite, затем откроет [http://127.0.0.1:5173](http://127.0.0.1:5173). Локальные логи и PID хранятся в игнорируемой папке `.runtime/`.

Ручной запуск:

```bash
npm install
cp .env.example .env  # только если .env ещё не создан
npm run dev
```

Для перевода через Gemini заполните `GEMINI_API_KEY`, переключите движок в настройках и выберите Flash-Lite 3.1 или 3.5. Настройки Codex можно переопределить переменными из [`.env.example`](.env.example).

## Конфигурация и секреты

Единственный локальный файл секретов — `.env`; он исключён из Git. Репозиторий содержит только безопасный шаблон [`.env.example`](.env.example):

- `GROQ_API_KEY` — STT на вебе и опциональный Groq STT в Android;
- `GEMINI_API_KEY` — перевод в вебе/Android и Gemini TTS в Android;
- `CODEX_CLI_PATH` — необязательный путь к Codex CLI для веб-провайдера;
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

- `src/` — React-интерфейс, запись звука, браузерный TTS и клиент API.
- `server/stt/` — серверный адаптер Groq Whisper.
- `server/translation/` — изолированные провайдеры Codex SDK и Gemini.
- `server/tts/` — серверный адаптер Gemini TTS для веб-клиента.
- `android/app/` — активное Jetpack Compose приложение.
- `android/optional/whisper/` — сохранённая, но не подключённая к APK экспериментальная on-device реализация только для Android.

Веб и Android используют одну и ту же системную инструкцию с каноническими именами и кодами выбранных языков. Адаптеры передают её как `developer_instructions` в Codex SDK, `instructions` в OpenAI Responses API и `systemInstruction` в Gemini; распознанный текст всегда отправляется отдельно как недоверенный user input. Ответ переводчика проверяется по минимальной JSON-схеме. Замеры этапов доступны как `[timing]` в веб-консоли/Node и через Android-тег `SayItTiming`.
