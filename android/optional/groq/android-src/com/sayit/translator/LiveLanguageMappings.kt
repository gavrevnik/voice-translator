package com.sayit.translator

import java.util.Locale

/**
 * The aliases used by Conversation Live mode are intentionally kept in one file so the
 * normalization policy can be adjusted without touching the voice-cycle orchestration.
 */
private data class LiveLanguageMapping(
    val canonicalName: String,
    val appLanguage: AppLanguage?,
    val aliases: Set<String>,
)

internal data class LiveDetectedLanguage(
    val rawValue: String,
    val canonicalName: String,
    val appLanguage: AppLanguage?,
)

private val LIVE_LANGUAGE_MAPPINGS = listOf(
    LiveLanguageMapping(
        canonicalName = "Serbian",
        appLanguage = AppLanguage.SERBIAN,
        aliases = setOf(
            "sr",
            "srp",
            "serbian",
            "hr",
            "hrv",
            "croatian",
            "bs",
            "bos",
            "bosnian",
        ),
    ),
    LiveLanguageMapping(
        canonicalName = "Romanian",
        appLanguage = AppLanguage.ROMANIAN,
        aliases = setOf(
            "ro",
            "ron",
            "rum",
            "romanian",
            "mo",
            "mol",
            "moldavian",
            "moldovan",
        ),
    ),
    LiveLanguageMapping(
        canonicalName = "English",
        appLanguage = AppLanguage.ENGLISH,
        aliases = setOf("en", "eng", "english"),
    ),
    LiveLanguageMapping(
        canonicalName = "Russian",
        appLanguage = AppLanguage.RUSSIAN,
        aliases = setOf("ru", "rus", "russian"),
    ),
    LiveLanguageMapping(
        canonicalName = "Spanish",
        appLanguage = AppLanguage.SPANISH,
        aliases = setOf("es", "spa", "spanish"),
    ),
)

internal fun mapLiveDetectedLanguage(rawLanguage: String): LiveDetectedLanguage {
    val raw = rawLanguage
        .replace(Regex("[^\\p{L}\\p{N}_ -]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_LIVE_LANGUAGE_NAME_LENGTH)
    val lookupValue = raw
        .lowercase(Locale.ROOT)
        .replace('_', '-')
        .substringBefore('-')
    val mapping = LIVE_LANGUAGE_MAPPINGS.firstOrNull { lookupValue in it.aliases }
    return LiveDetectedLanguage(
        rawValue = raw,
        canonicalName = mapping?.canonicalName ?: raw.toLiveLanguageDisplayName(),
        appLanguage = mapping?.appLanguage,
    )
}

internal fun liveLanguageFamily(language: AppLanguage): AppLanguage = when (language) {
    AppLanguage.CROATIAN -> AppLanguage.SERBIAN
    else -> language
}

internal fun resolveLiveSpeakerSide(
    detectedLanguage: LiveDetectedLanguage,
    languageA: AppLanguage,
    languageB: AppLanguage,
    fallbackSide: LanguageSide,
): LanguageSide {
    val detected = detectedLanguage.appLanguage ?: return fallbackSide
    val matchesA = detected == liveLanguageFamily(languageA)
    val matchesB = detected == liveLanguageFamily(languageB)
    return when {
        matchesA && !matchesB -> LanguageSide.A
        matchesB && !matchesA -> LanguageSide.B
        else -> fallbackSide
    }
}

internal fun liveTranscriptForDisplay(
    transcript: String,
    detectedLanguage: LiveDetectedLanguage,
    configuredLanguage: AppLanguage,
): String {
    val languageMatches = detectedLanguage.appLanguage == liveLanguageFamily(configuredLanguage)
    return if (languageMatches) {
        transcript
    } else {
        "Recognized language — ${detectedLanguage.canonicalName}\n\n$transcript"
    }
}

private fun String.toLiveLanguageDisplayName(): String {
    if (isBlank()) return "Unknown"
    if (length <= 3 && all(Char::isLetter)) return uppercase(Locale.ROOT)
    return replace('_', ' ')
        .replace('-', ' ')
        .lowercase(Locale.ROOT)
        .replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase(Locale.ROOT) else first.toString()
        }
}

private const val MAX_LIVE_LANGUAGE_NAME_LENGTH = 64
