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
    OFFLINE_OPUS("OPUS Slavic — Offline"),
}

enum class SerbianScript(val label: String, val targetToken: String) {
    LATIN("Serbian Latin", ">>srp_Latn<<"),
    CYRILLIC("Serbian Cyrillic", ">>srp_Cyrl<<"),
}

enum class TtsEngine(val label: String) {
    GEMINI("Gemini Flash"),
    SYSTEM("Android TTS"),
}

enum class SttEngine(val label: String) {
    SYSTEM("Android Speech"),
    GROQ("Groq Whisper"),
}

enum class TranslationOption(
    val label: String,
    val engine: TranslationEngine,
    val openAiModel: TranslationModel? = null,
    val geminiModel: GeminiTranslationModel? = null,
) {
    OPENAI_LUNA("GPT-5.6 Luna", TranslationEngine.OPENAI, openAiModel = TranslationModel.LUNA),
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
    OFFLINE_OPUS("OPUS Slavic — Offline", TranslationEngine.OFFLINE_OPUS),
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
    val ttsEngine: TtsEngine = TtsEngine.SYSTEM,
    val sttEngine: SttEngine = SttEngine.SYSTEM,
    val serbianScript: SerbianScript = SerbianScript.LATIN,
    val offlineModelStatus: OfflineModelStatus = OfflineModelStatus.NotInstalled,
    val offlineModelDownloadSizeLabel: String = "",
    val offlineRuntimeAvailable: Boolean = true,
    val status: VoiceStatus = VoiceStatus.READY,
    val activeSide: LanguageSide? = null,
    val textA: String = "",
    val textB: String = "",
    val resultSide: LanguageSide? = null,
    val elapsedSeconds: Int = 0,
    val error: String? = null,
    val hasOpenAiApiKey: Boolean = false,
)

const val GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview"
const val GROQ_STT_MODEL = "whisper-large-v3"

fun isOfflineOpusDirection(languageA: AppLanguage, languageB: AppLanguage): Boolean =
    setOf(languageA, languageB) == setOf(AppLanguage.RUSSIAN, AppLanguage.SERBIAN)
