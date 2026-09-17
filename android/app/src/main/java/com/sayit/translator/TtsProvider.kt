package com.sayit.translator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface TtsProvider {
    suspend fun speak(text: String, language: AppLanguage, onStarted: (() -> Unit)? = null)
    fun stop()
    fun release()
}

class SystemTtsProvider(context: Context) : TtsProvider {
    private val appContext = context.applicationContext
    private val sessions = linkedMapOf<String, TtsEngineSession>()
    private val installedEngines = mutableMapOf<AppLanguage, TtsEngineCandidate>()
    private val downloadEngines = mutableMapOf<AppLanguage, TtsEngineCandidate>()
    private var activeUtterance: CompletableDeferred<Unit>? = null
    private var activeUtteranceId: String? = null
    private var activeOnStarted: (() -> Unit)? = null
    private var activeLanguage: AppLanguage? = null
    private var activeEngineName = "Android"
    private var activeVoiceName = "unknown"
    private var activeVoiceLocaleTag = "unknown"

    override suspend fun speak(text: String, language: AppLanguage, onStarted: (() -> Unit)?) {
        val candidate = preferredInstalledEngine(language) ?: error(offlineVoiceMissingMessage(language))
        val session = session(candidate) ?: error(
            "Android text-to-speech could not start ${candidate.providerName}.",
        )
        val locale = Locale.forLanguageTag(language.bcp47)
        val offlineVoice = withContext(Dispatchers.Main.immediate) {
            findOfflineVoice(session.textToSpeech, locale)
        } ?: error(offlineVoiceMissingMessage(language))
        withContext(Dispatchers.Main.immediate) {
            stop()
            if (session.textToSpeech.setVoice(offlineVoice) == TextToSpeech.ERROR) {
                error(
                    "Android text-to-speech could not select the installed offline " +
                        "${language.canonicalName} voice from ${candidate.providerName}.",
                )
            }
            val utterance = CompletableDeferred<Unit>()
            val utteranceId = UUID.randomUUID().toString()
            activeUtterance = utterance
            activeUtteranceId = utteranceId
            activeOnStarted = onStarted
            activeLanguage = language
            activeEngineName = candidate.providerName
            activeVoiceName = offlineVoice.name
            activeVoiceLocaleTag = offlineVoice.locale.toLanguageTag()
            val status = session.textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                utteranceId,
            )
            if (status == TextToSpeech.ERROR) {
                clearActiveUtterance()
                error(systemTtsErrorMessage(TextToSpeech.ERROR, language))
            }
        }
        try {
            activeUtterance?.await()
        } finally {
            if (activeLanguage == language) clearActiveUtterance()
        }
    }

    internal suspend fun inspectOfflineLanguage(
        language: AppLanguage,
    ): AndroidLanguagePackStatus = withContext(Dispatchers.Main.immediate) {
        val checks = ttsEngines().map { candidate ->
            candidate to inspectEngine(candidate, language)
        }
        checks.firstOrNull { (_, status) -> status.isInstalled }?.let { (candidate, status) ->
            installedEngines[language] = candidate
            downloadEngines.remove(language)
            return@withContext status
        }
        installedEngines.remove(language)
        downloadEngines.remove(language)
        if (language == AppLanguage.SERBIAN) {
            val piperCheck = checks.firstOrNull { (candidate, _) ->
                isPiperTtsProvider(candidate.packageName)
            }
            if (piperCheck == null) {
                downloadEngines[language] = PIPER_TTS_CANDIDATE
                val piperInstalled = isPackageInstalled(PIPER_TTS_PACKAGE_NAME)
                return@withContext AndroidLanguagePackStatus(
                    availability = if (piperInstalled) {
                        AndroidLanguagePackAvailability.SETUP_REQUIRED
                    } else {
                        AndroidLanguagePackAvailability.DOWNLOADABLE
                    },
                    providerName = PIPER_TTS_PROVIDER_NAME,
                    detail = if (piperInstalled) {
                        "Open the Piper engine once, then return and refresh the package list."
                    } else {
                        "Install the offline Piper Serbian ONNX engine (about 100 MB)."
                    },
                )
            }
            downloadEngines[language] = piperCheck.first
            return@withContext AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.SETUP_REQUIRED,
                providerName = PIPER_TTS_PROVIDER_NAME,
                detail = "Open the Piper engine once, then return and refresh the package list.",
            )
        }
        checks.firstOrNull { (_, status) -> status.canDownload }?.let { (candidate, status) ->
            downloadEngines[language] = candidate
            return@withContext status
        }
        checks.firstOrNull { (_, status) ->
            status.availability == AndroidLanguagePackAvailability.UNKNOWN
        }?.second
            ?: checks.firstOrNull { (_, status) ->
                status.availability == AndroidLanguagePackAvailability.ERROR
            }?.second
            ?: AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNSUPPORTED,
                detail = "No supported Android TTS engine supports ${language.bcp47}.",
            )
    }

    internal fun installVoiceDataIntent(language: AppLanguage): Intent {
        val candidate = downloadEngines[language] ?: ttsEngines().firstOrNull()
        if (candidate?.packageName == PIPER_TTS_PACKAGE_NAME) {
            if (!isPackageInstalled(PIPER_TTS_PACKAGE_NAME)) {
                return Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(piperTtsDownloadUrl(Build.SUPPORTED_ABIS.toList())),
                )
            }
            if (isPiperTtsProvider(candidate.packageName)) {
                sessions.remove(candidate.packageName)?.textToSpeech?.shutdown()
                installedEngines.remove(language)
            }
            appContext.packageManager.getLaunchIntentForPackage(candidate.packageName)?.let {
                return it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return ttsSettingsIntent()
        }
        if (candidate != null) {
            val engineInstaller = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                .setPackage(candidate.packageName)
            if (engineInstaller.resolveActivity(appContext.packageManager) != null) {
                return engineInstaller
            }
        }
        return ttsSettingsIntent()
    }

    private fun ttsSettingsIntent(): Intent {
        val ttsSettings = Intent(ACTION_TTS_SETTINGS)
        return if (ttsSettings.resolveActivity(appContext.packageManager) != null) {
            ttsSettings
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }

    internal fun activeServiceName(): String = activeEngineName

    internal fun activeVoiceName(): String = activeVoiceName

    internal fun activeVoiceLocaleTag(): String = activeVoiceLocaleTag

    override fun stop() {
        sessions.values.forEach { session -> session.textToSpeech.stop() }
        activeUtterance?.complete(Unit)
        clearActiveUtterance()
    }

    override fun release() {
        stop()
        sessions.values.forEach { session -> session.textToSpeech.shutdown() }
        sessions.clear()
        installedEngines.clear()
        downloadEngines.clear()
    }

    private suspend fun preferredInstalledEngine(
        language: AppLanguage,
    ): TtsEngineCandidate? = installedEngines[language] ?: run {
        inspectOfflineLanguage(language)
        installedEngines[language]
    }

    private suspend fun inspectEngine(
        candidate: TtsEngineCandidate,
        language: AppLanguage,
    ): AndroidLanguagePackStatus {
        val session = session(candidate) ?: return AndroidLanguagePackStatus(
            availability = AndroidLanguagePackAvailability.ERROR,
            providerName = candidate.providerName,
            detail = "${candidate.providerName} text-to-speech could not start.",
        )
        val locale = Locale.forLanguageTag(language.bcp47)
        if (findOfflineVoice(session.textToSpeech, locale) != null) {
            return AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.INSTALLED,
                providerName = candidate.providerName,
            )
        }
        val matchingVoices = session.textToSpeech.voices.orEmpty().filter { voice ->
            voice.locale.language.equals(locale.language, ignoreCase = true)
        }
        val availability = session.textToSpeech.isLanguageAvailable(locale)
        return when {
            availability == TextToSpeech.LANG_NOT_SUPPORTED -> AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNSUPPORTED,
                providerName = candidate.providerName,
            )
            availability == TextToSpeech.LANG_MISSING_DATA ||
                matchingVoices.any { voice ->
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features
                } || matchingVoices.any(Voice::isNetworkConnectionRequired) ||
                availability >= TextToSpeech.LANG_AVAILABLE -> AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.DOWNLOADABLE,
                providerName = candidate.providerName,
            )
            else -> AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNKNOWN,
                providerName = candidate.providerName,
                detail = "${candidate.providerName} could not report offline voice data.",
            )
        }
    }

    private suspend fun session(candidate: TtsEngineCandidate): TtsEngineSession? {
        val session = sessions.getOrPut(candidate.packageName) { TtsEngineSession(candidate) }
        val timeoutMs = if (isPiperTtsProvider(candidate.packageName)) {
            PIPER_ENGINE_INIT_TIMEOUT_MS
        } else {
            ENGINE_INIT_TIMEOUT_MS
        }
        val ready = runCatching {
            withTimeoutOrNull(timeoutMs) {
                session.ready.await()
                true
            } ?: false
        }.getOrDefault(false)
        if (!ready) {
            sessions.remove(candidate.packageName)?.textToSpeech?.shutdown()
            return null
        }
        return session
    }

    private fun findOfflineVoice(textToSpeech: TextToSpeech, locale: Locale): Voice? =
        textToSpeech.voices
            .orEmpty()
            .asSequence()
            .filter { voice -> voice.locale.language.equals(locale.language, ignoreCase = true) }
            .filterNot(Voice::isNetworkConnectionRequired)
            .filterNot { voice ->
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features
            }
            .sortedWith(
                compareByDescending<Voice> { voice ->
                    voice.locale.toLanguageTag().equals(locale.toLanguageTag(), ignoreCase = true)
                }.thenByDescending { voice ->
                    voice.locale.country.equals(locale.country, ignoreCase = true)
                }.thenByDescending(Voice::getQuality),
            )
            .firstOrNull()

    @Suppress("DEPRECATION")
    private fun ttsEngines(): List<TtsEngineCandidate> {
        val discovered = appContext.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL,
        ).mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            if (!serviceInfo.enabled) return@mapNotNull null
            val packageName = serviceInfo.packageName
            if (!isSupportedAndroidTtsProvider(packageName)) return@mapNotNull null
            TtsEngineCandidate(
                packageName = packageName,
                providerName = androidSpeechProviderName(
                    packageName,
                    resolveInfo.loadLabel(appContext.packageManager)?.toString().orEmpty(),
                ),
            )
        }.toMutableList()
        Settings.Secure.getString(appContext.contentResolver, TTS_DEFAULT_ENGINE_SETTING)
            ?.takeIf(String::isNotBlank)
            ?.takeIf(::isSupportedAndroidTtsProvider)
            ?.takeIf { defaultPackage -> discovered.none { it.packageName == defaultPackage } }
            ?.let { defaultPackage ->
                discovered += TtsEngineCandidate(
                    packageName = defaultPackage,
                    providerName = androidSpeechProviderName(defaultPackage, defaultPackage),
                )
            }
        return discovered
            .distinctBy(TtsEngineCandidate::packageName)
            .sortedWith(
                compareBy<TtsEngineCandidate> {
                    androidSpeechProviderPriority(it.packageName)
                }.thenBy(TtsEngineCandidate::providerName),
            )
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getApplicationInfo(packageName, 0).enabled
    }.getOrDefault(false)

    private fun clearActiveUtterance() {
        activeUtterance = null
        activeUtteranceId = null
        activeOnStarted = null
        activeLanguage = null
    }

    private inner class TtsEngineSession(
        val candidate: TtsEngineCandidate,
    ) {
        val ready = CompletableDeferred<Unit>()
        val textToSpeech = TextToSpeech(
            appContext,
            { status ->
                if (!ready.isCompleted) {
                    if (status == TextToSpeech.SUCCESS) {
                        ready.complete(Unit)
                    } else {
                        ready.completeExceptionally(
                            IllegalStateException(
                                "${candidate.providerName} text-to-speech failed to start.",
                            ),
                        )
                    }
                }
            },
            candidate.packageName,
        ).apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) activeOnStarted?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) activeUtterance?.complete(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) {
                        activeUtterance?.completeExceptionally(
                            IllegalStateException(
                                systemTtsErrorMessage(TextToSpeech.ERROR, activeLanguage),
                            ),
                        )
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == activeUtteranceId) {
                        activeUtterance?.completeExceptionally(
                            IllegalStateException(systemTtsErrorMessage(errorCode, activeLanguage)),
                        )
                    }
                }
            })
        }
    }

    private companion object {
        const val ENGINE_INIT_TIMEOUT_MS = 5_000L
        const val PIPER_ENGINE_INIT_TIMEOUT_MS = 30_000L
        const val TTS_DEFAULT_ENGINE_SETTING = "tts_default_synth"
        const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
        val PIPER_TTS_CANDIDATE = TtsEngineCandidate(
            packageName = PIPER_TTS_PACKAGE_NAME,
            providerName = PIPER_TTS_PROVIDER_NAME,
        )
    }
}

private data class TtsEngineCandidate(
    val packageName: String,
    val providerName: String,
)

internal fun offlineVoiceMissingMessage(language: AppLanguage): String =
    "Offline ${language.canonicalName} voice is not installed in a supported Android TTS " +
        "engine. Open Android speech packages in Settings to install one."

internal fun systemTtsErrorMessage(errorCode: Int, language: AppLanguage?): String {
    val languageName = language?.canonicalName ?: "selected language"
    return when (errorCode) {
        TextToSpeech.ERROR_NOT_INSTALLED_YET ->
            "The offline $languageName voice is not installed yet. Install its voice data " +
                "from Android speech packages in Settings."

        TextToSpeech.ERROR_NETWORK,
        TextToSpeech.ERROR_NETWORK_TIMEOUT,
        -> "The selected $languageName voice requires a network connection. Install an offline " +
            "$languageName voice from Android speech packages in Settings."

        TextToSpeech.ERROR_SERVICE ->
            "Android text-to-speech service failed while playing $languageName. Verify that its " +
                "offline voice is installed, then restart Say it."

        TextToSpeech.ERROR_OUTPUT ->
            "Android text-to-speech could not access the audio output. Check media volume and " +
                "the active audio device."

        TextToSpeech.ERROR_SYNTHESIS ->
            "The installed $languageName voice could not synthesize this text. Try another " +
                "offline voice or TTS engine."

        TextToSpeech.ERROR_INVALID_REQUEST ->
            "Android text-to-speech rejected the $languageName playback request."

        else ->
            "Android text-to-speech playback failed for $languageName (error $errorCode). " +
                "Restart or change the preferred TTS engine in Android Settings."
    }
}
