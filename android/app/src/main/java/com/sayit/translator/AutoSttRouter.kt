package com.sayit.translator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.Locale

internal enum class AndroidSttAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

class AutoSttRouter(
    context: Context,
    private val systemSttProvider: SystemSttProvider,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "say_it_android_stt_capabilities",
        Context.MODE_PRIVATE,
    )

    suspend fun resolve(language: AppLanguage): SttEngine {
        val remembered = rememberedAvailability(language)
        val detected = remembered ?: systemSttProvider.offlineLanguageAvailability(language)
        if (detected == AndroidSttAvailability.AVAILABLE) remember(language, detected)
        return chooseAutoSttEngine(
            androidAvailability = detected,
            internetAvailable = hasValidatedInternet(),
            groqConfigured = BuildConfig.GROQ_API_KEY.isNotBlank(),
        )
    }

    fun recordAndroidSuccess(language: AppLanguage) {
        remember(language, AndroidSttAvailability.AVAILABLE)
    }

    fun recordAndroidFailure(language: AppLanguage, throwable: Throwable) {
        if (isMissingAndroidSpeechLanguage(throwable)) {
            remember(language, AndroidSttAvailability.UNAVAILABLE)
        }
    }

    private fun rememberedAvailability(language: AppLanguage): AndroidSttAvailability? {
        val raw = preferences.getString(cacheKey(language), null) ?: return null
        val record = AndroidSttCapabilityRecord.parse(raw) ?: return null
        if (
            record.availability == AndroidSttAvailability.UNAVAILABLE &&
            System.currentTimeMillis() - record.recordedAtMs >= UNAVAILABLE_CACHE_TTL_MS
        ) {
            preferences.edit().remove(cacheKey(language)).apply()
            return null
        }
        return record.availability
    }

    private fun remember(language: AppLanguage, availability: AndroidSttAvailability) {
        val record = AndroidSttCapabilityRecord(availability, System.currentTimeMillis())
        preferences.edit().putString(cacheKey(language), record.serialize()).apply()
    }

    private fun cacheKey(language: AppLanguage): String =
        "${systemSttProvider.recognitionServiceKey()}|${language.bcp47}"

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val UNAVAILABLE_CACHE_TTL_MS = 60 * 60 * 1_000L
    }
}

internal data class AndroidSttCapabilityRecord(
    val availability: AndroidSttAvailability,
    val recordedAtMs: Long,
) {
    fun serialize(): String = "${availability.name}:$recordedAtMs"

    companion object {
        fun parse(raw: String): AndroidSttCapabilityRecord? {
            val parts = raw.split(':', limit = 2)
            if (parts.size != 2) return null
            val availability = AndroidSttAvailability.entries.firstOrNull {
                it.name == parts[0]
            } ?: return null
            val recordedAtMs = parts[1].toLongOrNull() ?: return null
            return AndroidSttCapabilityRecord(availability, recordedAtMs)
        }
    }
}

internal fun languageTagMatches(candidate: String, requested: String): Boolean {
    val candidateLocale = Locale.forLanguageTag(candidate.replace('_', '-'))
    val requestedLocale = Locale.forLanguageTag(requested.replace('_', '-'))
    if (candidateLocale.language.isBlank() || requestedLocale.language.isBlank()) return false
    return candidateLocale.language.equals(requestedLocale.language, ignoreCase = true)
}
