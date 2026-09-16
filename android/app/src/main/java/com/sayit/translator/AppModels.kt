package com.sayit.translator

enum class AppLanguage(
    val code: String,
    val canonicalName: String,
    val nativeName: String,
    val bcp47: String,
    val whisperCode: String,
    val speakLabel: String,
    val stopLabel: String,
) {
    SERBIAN("sr", "Serbian", "Српски", "sr-RS", "sr", "Говори", "Заустави"),
    CROATIAN("hr", "Croatian", "Hrvatski", "hr-HR", "hr", "Govori", "Zaustavi"),
    ENGLISH("en", "English", "English", "en-US", "en", "Speak", "Stop"),
    ROMANIAN("ro", "Romanian", "Română", "ro-RO", "ro", "Vorbește", "Oprește"),
    RUSSIAN("ru", "Russian", "Русский", "ru-RU", "ru", "Говорить", "Стоп"),
    SPANISH("es", "Spanish", "Español", "es-ES", "es", "Habla", "Detener"),
}

enum class LanguageSide { A, B }

enum class TranslationModel(val id: String) {
    LUNA("gpt-5.6-luna"),
}

enum class GeminiTranslationModel(val id: String) {
    FLASH_3_1_LITE("gemini-3.1-flash-lite"),
    FLASH_3_5_LITE("gemini-3.5-flash-lite"),
}

enum class TranslationEngine(val label: String) {
    OPENAI("OpenAI API"),
    GEMINI("Gemini Flash"),
    OFFLINE_OPUS_SLAVIC("OPUS Slavic FP32 — Offline"),
    OFFLINE_OPUS_INDO_EUROPEAN("OPUS Indo-European FP32 — Offline"),
}

enum class SerbianScript(val label: String, val targetToken: String) {
    LATIN("Serbian Latin", ">>srp_Latn<<"),
    CYRILLIC("Serbian Cyrillic", ">>srp_Cyrl<<"),
}

enum class SttEngine(val label: String, val progressLabel: String) {
    AUTO("Auto", "Auto"),
    SYSTEM("Android Speech", "Android"),
    GROQ("Groq Whisper", "Groq Whisper"),
    WHISPER_OFFLINE("Whisper Offline", "Whisper Offline"),
    WHISPER_OFFLINE_LIVE("Whisper Offline Live", "Whisper Live"),
}

enum class TranslationOption(
    val label: String,
    val progressLabel: String,
    val engine: TranslationEngine,
    val openAiModel: TranslationModel? = null,
    val geminiModel: GeminiTranslationModel? = null,
) {
    OPENAI_LUNA(
        "GPT-5.6 Luna",
        "GPT Luna",
        TranslationEngine.OPENAI,
        openAiModel = TranslationModel.LUNA,
    ),
    GEMINI_3_1(
        "Gemini Flash 3.1",
        "Gemini 3.1",
        TranslationEngine.GEMINI,
        geminiModel = GeminiTranslationModel.FLASH_3_1_LITE,
    ),
    GEMINI_3_5(
        "Gemini Flash 3.5",
        "Gemini 3.5",
        TranslationEngine.GEMINI,
        geminiModel = GeminiTranslationModel.FLASH_3_5_LITE,
    ),
    OFFLINE_OPUS_SLAVIC(
        "OPUS Slavic FP32 — Offline",
        "Slavic FP32",
        TranslationEngine.OFFLINE_OPUS_SLAVIC,
    ),
    OFFLINE_OPUS_INDO_EUROPEAN(
        "OPUS Indo-European FP32 — Offline",
        "INE FP32",
        TranslationEngine.OFFLINE_OPUS_INDO_EUROPEAN,
    ),
    ;

    companion object {
        fun from(state: TranslatorUiState): TranslationOption = entries.first { option ->
            option.engine == state.translationEngine &&
                (option.openAiModel == null || option.openAiModel == state.model) &&
                (option.geminiModel == null || option.geminiModel == state.geminiModel)
        }
    }
}

enum class VoiceStatus(val label: String) {
    READY("Ready"),
    LISTENING("Listening"),
    RECOGNIZING("Recognizing"),
    TRANSLATING("Translating"),
    SPEAKING("Speaking"),
    ERROR("Error"),
}

data class TranslationResult(
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
    val translatedText: String,
)

data class TranslatorUiState(
    val languageA: AppLanguage = AppLanguage.RUSSIAN,
    val languageB: AppLanguage = AppLanguage.ENGLISH,
    val model: TranslationModel = TranslationModel.LUNA,
    val geminiModel: GeminiTranslationModel = GeminiTranslationModel.FLASH_3_1_LITE,
    val translationEngine: TranslationEngine = TranslationEngine.GEMINI,
    val sttEngine: SttEngine = SttEngine.AUTO,
    val activeSttEngine: SttEngine? = null,
    val serbianScript: SerbianScript = SerbianScript.LATIN,
    val offlineModelStatus: OfflineModelStatus = OfflineModelStatus.NotInstalled,
    val offlineModelDownloadSizeLabel: String = "",
    val offlineRuntimeAvailable: Boolean = true,
    val offlineIneModelStatus: OfflineModelStatus = OfflineModelStatus.NotInstalled,
    val offlineIneModelDownloadSizeLabel: String = "",
    val whisperModelStatus: OfflineModelStatus = OfflineModelStatus.NotInstalled,
    val whisperModelDownloadSizeLabel: String = "",
    val whisperRuntimeAvailable: Boolean = true,
    val status: VoiceStatus = VoiceStatus.READY,
    val activeSide: LanguageSide? = null,
    val partialTranscriptSide: LanguageSide? = null,
    val textA: String = "",
    val textB: String = "",
    val resultSide: LanguageSide? = null,
    val elapsedSeconds: Int = 0,
    val error: String? = null,
    val hasOpenAiApiKey: Boolean = false,
)

const val GROQ_STT_MODEL = "whisper-large-v3"
const val WHISPER_OFFLINE_MODEL = "small-q5_1"
const val PLAYBACK_PROGRESS_LABEL = "Android"

fun recognitionProgressLabel(selected: SttEngine, active: SttEngine?): String =
    if (selected == SttEngine.AUTO && active != null && active != SttEngine.AUTO) {
        "Auto (${active.progressLabel})"
    } else {
        selected.progressLabel
    }

internal fun chooseAutoSttEngine(
    androidAvailability: AndroidSttAvailability,
    internetAvailable: Boolean,
    groqConfigured: Boolean,
): SttEngine = when {
    androidAvailability != AndroidSttAvailability.UNAVAILABLE -> SttEngine.SYSTEM
    internetAvailable && groqConfigured -> SttEngine.GROQ
    else -> SttEngine.WHISPER_OFFLINE_LIVE
}

fun SttEngine.isWhisperOffline(): Boolean =
    this == SttEngine.WHISPER_OFFLINE || this == SttEngine.WHISPER_OFFLINE_LIVE

fun isOfflineSlavicDirection(languageA: AppLanguage, languageB: AppLanguage): Boolean =
    (languageA == AppLanguage.RUSSIAN &&
        languageB in setOf(AppLanguage.SERBIAN, AppLanguage.CROATIAN)) ||
        (languageB == AppLanguage.RUSSIAN &&
            languageA in setOf(AppLanguage.SERBIAN, AppLanguage.CROATIAN))

fun isOfflineIndoEuropeanDirection(languageA: AppLanguage, languageB: AppLanguage): Boolean =
    (languageA == AppLanguage.RUSSIAN &&
        languageB in setOf(AppLanguage.ROMANIAN, AppLanguage.SPANISH)) ||
        (languageB == AppLanguage.RUSSIAN &&
            languageA in setOf(AppLanguage.ROMANIAN, AppLanguage.SPANISH))

fun isOfflineOpusDirection(
    engine: TranslationEngine,
    languageA: AppLanguage,
    languageB: AppLanguage,
): Boolean = when (engine) {
    TranslationEngine.OFFLINE_OPUS_SLAVIC -> isOfflineSlavicDirection(languageA, languageB)
    TranslationEngine.OFFLINE_OPUS_INDO_EUROPEAN ->
        isOfflineIndoEuropeanDirection(languageA, languageB)
    else -> false
}

fun TranslationEngine.isOfflineOpus(): Boolean =
    this == TranslationEngine.OFFLINE_OPUS_SLAVIC ||
        this == TranslationEngine.OFFLINE_OPUS_INDO_EUROPEAN
