package com.sayit.translator

enum class AppLanguage(
    val code: String,
    val canonicalName: String,
    val nativeName: String,
    val bcp47: String,
    val speakLabel: String,
    val stopLabel: String,
) {
    SERBIAN("sr", "Serbian", "Српски", "sr-RS", "Говори", "Заустави"),
    CROATIAN("hr", "Croatian", "Hrvatski", "hr-HR", "Govori", "Zaustavi"),
    ENGLISH("en", "English", "English", "en-US", "Speak", "Stop"),
    ROMANIAN("ro", "Romanian", "Română", "ro-RO", "Vorbește", "Oprește"),
    RUSSIAN("ru", "Russian", "Русский", "ru-RU", "Говорить", "Стоп"),
    SPANISH("es", "Spanish", "Español", "es-ES", "Habla", "Detener"),
}

enum class LanguageSide { A, B }

enum class GeminiTranslationModel(val id: String) {
    FLASH_3_1_LITE("gemini-3.1-flash-lite"),
    FLASH_3_5_LITE("gemini-3.5-flash-lite"),
}

enum class TranslationEngine(val label: String) {
    GEMINI("Gemini Flash"),
}

enum class SttEngine(val label: String) {
    GEMINI_TRANSCRIBE_LIVE("Gemini 3.5 Transcribe Live"),
}

enum class PlaybackEngine(val label: String) {
    ANDROID_SPEECH("Android Speech"),
}

enum class TranslationOption(
    val label: String,
    val engine: TranslationEngine,
    val geminiModel: GeminiTranslationModel,
) {
    GEMINI_3_1(
        "Gemini Flash 3.1",
        TranslationEngine.GEMINI,
        geminiModel = GeminiTranslationModel.FLASH_3_1_LITE,
    ),
    GEMINI_3_5(
        "Gemini Flash 3.5",
        TranslationEngine.GEMINI,
        geminiModel = GeminiTranslationModel.FLASH_3_5_LITE,
    ),
    ;

    companion object {
        fun from(state: TranslatorUiState): TranslationOption = entries.first { option ->
            option.geminiModel == state.geminiModel
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
    val geminiModel: GeminiTranslationModel = GeminiTranslationModel.FLASH_3_5_LITE,
    val translationEngine: TranslationEngine = TranslationEngine.GEMINI,
    val sttEngine: SttEngine = SttEngine.GEMINI_TRANSCRIBE_LIVE,
    val androidTtsLanguagePacks: Map<AppLanguage, AndroidLanguagePackStatus> = emptyMap(),
    val status: VoiceStatus = VoiceStatus.READY,
    val liveModeActive: Boolean = false,
    val activeSide: LanguageSide? = null,
    val partialTranscriptSide: LanguageSide? = null,
    val textA: String = "",
    val textB: String = "",
    val resultSide: LanguageSide? = null,
    val elapsedSeconds: Int = 0,
    val error: String? = null,
    val hasLastDiagnostics: Boolean = false,
)

const val GEMINI_TRANSCRIBE_LIVE_MODEL = "gemini-3.5-transcribe-live"
const val GEMINI_SERVER_VAD_SILENCE_MS = 700
const val GEMINI_SERVER_VAD_PREFIX_PADDING_MS = 250
const val GEMINI_LOCAL_RMS_FALLBACK_MS = 1_000L

fun SttEngine.supportsConversationLive(): Boolean =
    this == SttEngine.GEMINI_TRANSCRIBE_LIVE
