# Say it — Android

Нативный Jetpack Compose клиент для двустороннего голосового перевода на Android 8+ (`minSdk 26`). Поддерживаются сербский, хорватский, английский, румынский, русский и испанский языки. Офлайн-перевод требует arm64 и Android 9+ (API 28).

## Движки

- Распознавание: **Android/Samsung SpeechRecognizer** по умолчанию, **Groq Whisper Large V3** или локальный **Whisper Offline** (`base-q5_1`, multilingual).
- Перевод: **Gemini Flash-Lite** по умолчанию с выбором `gemini-3.1-flash-lite` или `gemini-3.5-flash-lite`; обе используют thinking `minimal`. Альтернативы — **OpenAI API** с `gpt-5.6-luna` и фиксированным reasoning `none`, а также **OPUS Slavic — Offline** для `Russian ↔ Serbian` и `Russian ↔ Croatian`.
- Озвучивание: только **Android System TTS**.

OpenAI-ключ вводится пользователем в настройках приложения и хранится с AES-GCM на ключе из Android Keystore. Gemini translation использует `GEMINI_API_KEY`, а Groq STT — `GROQ_API_KEY`; оба значения читаются из корневого `.env` во время сборки.

Офлайн OPUS отвечает только за перевод. Для полностью автономной цепочки можно выбрать Whisper Offline вместо системного STT; Android TTS всё равно требует установленный локальный голос целевого языка. Приложение выбирает только TTS-голос, не требующий сети, и выводит отдельные понятные ошибки для отсутствующего голоса, неподдерживаемого системного языка распознавания (`error 12`) и нескачанной системной модели распознавания (`error 13`).

Главный экран рассчитан на узкие телефоны: селекторы языков и кнопки речи компактны, а исходная фраза и перевод идут полноширинными карточками друг под другом, растут по содержимому и прокручиваются вместе с экраном. Нажатие на любую заполненную карточку открывает текст на весь экран, а перевод можно скопировать отдельной кнопкой. Однострочная шкала показывает короткие имена реально выбранных движков, например `Android → Gemini 3.1 → Android`; справа во время записи виден таймер, а во время озвучивания — квадратная кнопка остановки.

Настройки открываются отдельным прокручиваемым экраном. В порядке pipeline идут списки распознавания и перевода; Android TTS теперь фиксирован и не требует отдельного выбора. Ниже показываются только статусы API-ключей. OpenAI-ключ добавляется или заменяется в отдельном защищённом диалоге. Системная инструкция перевода совпадает с веб-версией, содержит канонические имена и коды языков, а transcript отправляется отдельным user input.

## Whisper Offline

Локальное распознавание использует multilingual Whisper Base в квантизации `Q5_1`. Оно доступно для всех шести языков приложения и не зависит от языковых пакетов Samsung/Google. Нативный arm64 runtime входит в APK, а веса скачиваются отдельно после выбора **Whisper Offline** в настройках:

- модель: `ggml-base-q5_1.bin`;
- скачивание и размер после установки: `59 707 625` байт (59,7 MB);
- SHA-256: `80147c665f9ac54ab8c3c7f55da90ac030ea40610495ba4dd0aa6304fd49d731`;
- локальный artifact: `model-packs/ggml-base-q5_1.bin` (игнорируется Git).

Delivery manifest находится в `app/src/main/assets/whisper_models.json` и указывает на GitHub Release `offline-whisper-base-q5_1-v1`. До публикации asset `ggml-base-q5_1.bin` встроенная загрузка вернёт 404. URL можно переопределить через `OFFLINE_WHISPER_MODEL_URL` в корневом `.env`. Квантизация исходной multilingual Base-модели воспроизводится командой `tools/quantize_whisper_model.sh`; нативный runtime собран из закреплённого commit `whisper.cpp` и хранится в `app/libs/whisperlib-release.aar` без весов.

## OPUS Slavic — Offline

Android использует квантизованный `Helsinki-NLP/opus-mt-sla-sla` через Bergamot/Marian и локально собранный `translate-kit` AAR. Исходная модель знает более широкий набор славянских вариантов (`bel`, `bos_Latn`, `bul`, `ces`, `hrv`, `mkd`, `pol`, `rus`, `slv`, `srp_Latn`, `srp_Cyrl`, `ukr` и другие), но приложение разрешает провайдер только для пар `ru ↔ sr` и `ru ↔ hr`. При другой паре пункт неактивен, а дополнительная проверка в ViewModel и provider не позволяет обойти ограничение.

Для направления RU→SR доступны `>>srp_Latn<<` (по умолчанию) и `>>srp_Cyrl<<`; RU→HR использует `>>hrv<<`; для обратных направлений используется `>>rus<<`. Автоопределение языка этому provider не требуется. Android Speech и Android TTS используют для хорватского системную локаль `hr-HR`, поэтому их офлайн-работа зависит от установленных Google-пакетов распознавания и голоса.

ModelManager поддерживает состояния `NotInstalled`, `Downloading(progress)`, `Installed`, `Invalid` и `Error`, проверяет SHA-256 архива и каждого runtime-файла, безопасно распаковывает ZIP и хранит модель в `filesDir`, поэтому обычное обновление APK её не удаляет. Веса не входят в APK и Git:

- скачивание: `45 045 954` байт (45,0 MB);
- после установки: `66 813 730` байт (66,8 MB);
- основной INT8-файл: `64 176 671` байт;
- локальный готовый artifact: `model-packs/offline-opus-sla-int8-v1.zip` (игнорируется Git).

Delivery manifest находится в `app/src/main/assets/offline_models.json`. Его стандартный URL указывает на GitHub Release `offline-opus-sla-v1`; до публикации одноимённого release asset встроенная загрузка вернёт 404. Для локального/CDN-теста можно задать `OFFLINE_OPUS_MODEL_URL` в корневом `.env` перед сборкой.

Воспроизводимая конвертация исходного Marian checkpoint в `intgemm8`, сборка совместимых SentencePiece-словарей и shortlist выполняются скриптом:

```bash
python3 tools/build_offline_opus_pack.py \
  --marian-conv /path/to/x86_64/marian-conv \
  --work-dir /tmp/sayit-opus-build \
  --output-dir model-packs \
  --app-manifest app/src/main/assets/offline_models.json
```

Скрипт при отсутствии `--source-dir` сам скачивает закреплённый архив OPUS и проверяет его checksum. Web-сравнение original/quantized в этот pipeline намеренно не входит. JNI AAR без локальных изменений можно пересобрать из закреплённого upstream commit командой `tools/build_translate_kit_aar.sh`; лицензии и notices лежат в `app/libs/`.

## Сборка

Создайте корневой `.env` из шаблона и заполните ключи:

```bash
cp ../.env.example ../.env
```

Затем откройте каталог `android` в Android Studio либо используйте JDK 17 и терминал:

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Release APK будет создан в `app/build/outputs/apk/release/`. Build-каталоги, APK и model pack исключены из Git.

> Важно: Gradle встраивает Gemini и Groq ключи в APK. Их можно извлечь, поэтому такую сборку нельзя публиковать или передавать третьим лицам. Для распространяемой версии используйте backend и отзовите тестовые ключи.

## Использование

1. Выберите два языка и нужные движки в настройках.
2. Нажмите микрофон под исходным языком и говорите без ограничения по паузам.
3. Нажмите ту же кнопку ещё раз: финальный текст будет переведён и озвучен.
4. Кнопка **Stop** останавливает воспроизведение; кнопка play повторяет последний перевод.

System SpeechRecognizer может показывать частичный текст во время записи. Groq и Whisper Offline работают пакетно и возвращают результат после остановки. В APK входит только нативный Whisper runtime; 59,7 MB весов загружаются и хранятся отдельно в app-private storage.

Для диагностики задержек используйте:

```bash
adb logcat -s SayItTiming
```
