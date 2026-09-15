# Say it — Android

Нативный Jetpack Compose клиент для двустороннего голосового перевода на Android 8+ (`minSdk 26`). Поддерживаются сербский, английский, румынский, русский и испанский языки. Офлайн-перевод требует arm64 и Android 9+ (API 28).

## Движки

- Распознавание: **Android/Samsung SpeechRecognizer** по умолчанию или **Groq Whisper Large V3**.
- Перевод: **Gemini Flash-Lite** по умолчанию с выбором `gemini-3.1-flash-lite` или `gemini-3.5-flash-lite`; обе используют thinking `minimal`. Альтернативы — **OpenAI API** с `gpt-5.6-luna` и фиксированным reasoning `none`, а также **OPUS Slavic — Offline** для `Russian ↔ Serbian`.
- Озвучивание: **Android System TTS** по умолчанию или **Gemini 3.1 Flash TTS Preview** (`gemini-3.1-flash-tts-preview`).

OpenAI-ключ вводится пользователем в настройках приложения и хранится с AES-GCM на ключе из Android Keystore. Gemini translation/TTS используют один `GEMINI_API_KEY`, а Groq STT — `GROQ_API_KEY`; оба значения читаются из корневого `.env` во время сборки.

Главный экран рассчитан на узкие телефоны: селекторы языков и кнопки речи компактны, а исходная фраза и перевод идут полноширинными карточками друг под другом, растут по содержимому и прокручиваются вместе с экраном. Нажатие на любую заполненную карточку открывает текст на весь экран, а перевод можно скопировать отдельной кнопкой. Однострочная шкала показывает `Speech → выбранная модель → Playback`; справа от неё во время записи виден таймер, а во время озвучивания — квадратная кнопка остановки.

Настройки открываются отдельным прокручиваемым экраном. Три выпадающих списка идут в порядке pipeline: распознавание, перевод, воспроизведение; ниже показываются только статусы API-ключей. OpenAI-ключ добавляется или заменяется в отдельном защищённом диалоге. Системная инструкция перевода совпадает с веб-версией, содержит канонические имена и коды языков, а transcript отправляется отдельным user input.

## OPUS Slavic — Offline

Android использует квантизованный `Helsinki-NLP/opus-mt-sla-sla` через Bergamot/Marian и локально собранный `translate-kit` AAR. Исходная модель знает более широкий набор славянских вариантов (`bel`, `bos_Latn`, `bul`, `ces`, `hrv`, `mkd`, `pol`, `rus`, `slv`, `srp_Latn`, `srp_Cyrl`, `ukr` и другие), но приложение намеренно разрешает провайдер только для проверенной пары `ru ↔ sr`. При другой паре пункт неактивен, а дополнительная проверка в ViewModel и provider не позволяет обойти ограничение.

Для направления RU→SR доступны `>>srp_Latn<<` (по умолчанию) и `>>srp_Cyrl<<`; для SR→RU используется `>>rus<<`. Автоопределение языка этому provider не требуется.

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

System SpeechRecognizer может показывать частичный текст во время записи. Groq работает пакетно и возвращает результат после остановки. Сохранённая on-device Whisper-реализация лежит в [`optional/whisper`](optional/whisper/README.md), но её модель и native-модуль не входят в текущий APK.

Для диагностики задержек используйте:

```bash
adb logcat -s SayItTiming
```
