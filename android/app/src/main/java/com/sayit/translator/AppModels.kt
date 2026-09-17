package com.sayit.translator

import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

enum class LayoutMode(val label: String) {
    SINGLE("Single mode"),
    CONVERSATION("Conversation mode"),
}

enum class GeminiTranslationModel(val id: String) {
    FLASH_3_1_LITE("gemini-3.1-flash-lite"),
    FLASH_3_5_LITE("gemini-3.5-flash-lite"),
}

enum class TranslationEngine(val label: String) {
    GEMINI("Gemini Flash"),
    OFFLINE_OPUS_SLAVIC("OPUS Slavic FP32 — Offline"),
}

enum class SerbianScript(val label: String, val targetToken: String) {
    LATIN("Serbian Latin", ">>srp_Latn<<"),
    CYRILLIC("Serbian Cyrillic", ">>srp_Cyrl<<"),
}

enum class SttEngine(val label: String, val progressLabel: String) {
    SYSTEM("Android Speech", "Android"),
    GROQ("Groq Whisper", "Groq Whisper"),
    GEMINI_TRANSCRIBE_LIVE("Gemini 3.5 Transcribe Live", "Gemini Live STT"),
    WHISPER_OFFLINE("Whisper Offline", "Whisper Offline"),
}

enum class PlaybackEngine(val label: String) {
    ANDROID_SPEECH("Android Speech"),
}

enum class TranslationOption(
    val label: String,
    val progressLabel: String,
    val engine: TranslationEngine,
    val geminiModel: GeminiTranslationModel? = null,
) {
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
    ;

    companion object {
        fun from(state: TranslatorUiState): TranslationOption = entries.first { option ->
            option.engine == state.translationEngine &&
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
    val layoutMode: LayoutMode = LayoutMode.SINGLE,
    val geminiModel: GeminiTranslationModel = GeminiTranslationModel.FLASH_3_5_LITE,
    val translationEngine: TranslationEngine = TranslationEngine.GEMINI,
    val sttEngine: SttEngine = SttEngine.GEMINI_TRANSCRIBE_LIVE,
    val serbianScript: SerbianScript = SerbianScript.LATIN,
    val offlineModelStatus: OfflineModelStatus = OfflineModelStatus.NotInstalled,
    val offlineModelDownloadSizeLabel: String = "",
    val offlineRuntimeAvailable: Boolean = true,
    val whisperModelStatus: OfflineModelStatus = OfflineModelStatus.NotInstalled,
    val whisperModelDownloadSizeLabel: String = "",
    val whisperRuntimeAvailable: Boolean = true,
    val androidSttLanguagePacks: Map<AppLanguage, AndroidLanguagePackStatus> = emptyMap(),
    val androidTtsLanguagePacks: Map<AppLanguage, AndroidLanguagePackStatus> = emptyMap(),
    val status: VoiceStatus = VoiceStatus.READY,
    val liveModeActive: Boolean = false,
    val activeSide: LanguageSide? = null,
    val partialTranscriptSide: LanguageSide? = null,
    val textA: String = "",
    val textB: String = "",
    val resultSide: LanguageSide? = null,
    val elapsedSeconds: Int = 0,
    val silenceAutoStopSeconds: Float = DEFAULT_SILENCE_AUTO_STOP_SECONDS,
    val error: String? = null,
    val hasLastDiagnostics: Boolean = false,
)

const val GROQ_STT_MODEL = "whisper-large-v3"
const val GEMINI_TRANSCRIBE_LIVE_MODEL = "gemini-3.5-transcribe-live"
const val WHISPER_OFFLINE_MODEL = "large-v3-turbo-q4_0"
const val PLAYBACK_PROGRESS_LABEL = "Android"
const val DEFAULT_SILENCE_AUTO_STOP_SECONDS = 2f
const val MIN_SILENCE_AUTO_STOP_SECONDS = 0.1f
const val MAX_SILENCE_AUTO_STOP_SECONDS = 5f

internal fun normalizeSilenceAutoStopSeconds(seconds: Float): Float {
    if (!seconds.isFinite()) return DEFAULT_SILENCE_AUTO_STOP_SECONDS
    val clamped = seconds.coerceIn(
        MIN_SILENCE_AUTO_STOP_SECONDS,
        MAX_SILENCE_AUTO_STOP_SECONDS,
    )
    return (clamped * 10f).roundToInt() / 10f
}

internal fun parseSilenceAutoStopSeconds(raw: String): Float? {
    val normalized = raw.trim().replace(',', '.')
    if (!normalized.matches(Regex("""\d(?:\.\d)?"""))) return null
    return normalized.toFloatOrNull()?.takeIf {
        it in MIN_SILENCE_AUTO_STOP_SECONDS..MAX_SILENCE_AUTO_STOP_SECONDS
    }
}

internal fun formatSilenceAutoStopSeconds(seconds: Float): String {
    val tenths = (normalizeSilenceAutoStopSeconds(seconds) * 10f).roundToInt()
    return if (tenths % 10 == 0) {
        (tenths / 10).toString()
    } else {
        "${tenths / 10}.${tenths % 10}"
    }
}

internal fun silenceAutoStopDurationMs(seconds: Float): Long =
    (normalizeSilenceAutoStopSeconds(seconds) * 1_000f).roundToLong()

fun SttEngine.isWhisperOffline(): Boolean =
    this == SttEngine.WHISPER_OFFLINE

fun SttEngine.supportsConversationLive(): Boolean =
    this == SttEngine.GROQ ||
        this == SttEngine.GEMINI_TRANSCRIBE_LIVE

fun isOfflineSlavicDirection(languageA: AppLanguage, languageB: AppLanguage): Boolean =
    (languageA == AppLanguage.RUSSIAN &&
        languageB in setOf(AppLanguage.SERBIAN, AppLanguage.CROATIAN)) ||
        (languageB == AppLanguage.RUSSIAN &&
            languageA in setOf(AppLanguage.SERBIAN, AppLanguage.CROATIAN))

fun isOfflineOpusDirection(
    engine: TranslationEngine,
    languageA: AppLanguage,
    languageB: AppLanguage,
): Boolean = when (engine) {
    TranslationEngine.OFFLINE_OPUS_SLAVIC -> isOfflineSlavicDirection(languageA, languageB)
    else -> false
}

fun TranslationEngine.isOfflineOpus(): Boolean =
    this == TranslationEngine.OFFLINE_OPUS_SLAVIC
