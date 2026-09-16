package com.sayit.translator

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID

interface TtsProvider {
    suspend fun speak(text: String, language: AppLanguage, onStarted: (() -> Unit)? = null)
    fun stop()
    fun release()
}

class SystemTtsProvider(context: Context) : TtsProvider {
    private val ready = CompletableDeferred<Unit>()
    private var activeUtterance: CompletableDeferred<Unit>? = null
    private var activeUtteranceId: String? = null
    private var activeOnStarted: (() -> Unit)? = null
    private var activeLanguage: AppLanguage? = null
    private val textToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready.complete(Unit)
        else ready.completeExceptionally(IllegalStateException("System text-to-speech failed to start."))
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) activeOnStarted?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) activeUtterance?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) activeUtterance?.completeExceptionally(
                    IllegalStateException(systemTtsErrorMessage(TextToSpeech.ERROR, activeLanguage)),
                )
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == activeUtteranceId) activeUtterance?.completeExceptionally(
                    IllegalStateException(systemTtsErrorMessage(errorCode, activeLanguage)),
                )
            }
        })
    }

    override suspend fun speak(text: String, language: AppLanguage, onStarted: (() -> Unit)?) {
        ready.await()
        stop()
        val locale = Locale.forLanguageTag(language.bcp47)
        val offlineVoice = findOfflineVoice(locale) ?: error(offlineVoiceMissingMessage(language))
        if (textToSpeech.setVoice(offlineVoice) == TextToSpeech.ERROR) {
            error(
                "Android text-to-speech could not select the installed offline " +
                    "${language.canonicalName} voice. Restart or change the preferred TTS engine " +
                    "in Android Settings.",
            )
        }
        val utterance = CompletableDeferred<Unit>()
        val utteranceId = UUID.randomUUID().toString()
        activeUtterance = utterance
        activeUtteranceId = utteranceId
        activeOnStarted = onStarted
        activeLanguage = language
        try {
            val status = textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                utteranceId,
            )
            if (status == TextToSpeech.ERROR) {
                error(systemTtsErrorMessage(TextToSpeech.ERROR, language))
            }
            utterance.await()
        } finally {
            if (activeUtteranceId == utteranceId) clearActiveUtterance()
        }
    }

    private fun findOfflineVoice(locale: Locale): Voice? = textToSpeech.voices
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

    override fun stop() {
        textToSpeech.stop()
        activeUtterance?.complete(Unit)
        clearActiveUtterance()
    }

    private fun clearActiveUtterance() {
        activeUtterance = null
        activeUtteranceId = null
        activeOnStarted = null
        activeLanguage = null
    }

    override fun release() {
        stop()
        textToSpeech.shutdown()
    }
}

internal fun offlineVoiceMissingMessage(language: AppLanguage): String =
    "Offline ${language.canonicalName} voice is not installed. Open Android Settings → " +
        "General management → Text-to-speech → Preferred engine → Install voice data, " +
        "install ${language.canonicalName}, then restart Say it."

internal fun systemTtsErrorMessage(errorCode: Int, language: AppLanguage?): String {
    val languageName = language?.canonicalName ?: "selected language"
    return when (errorCode) {
        TextToSpeech.ERROR_NOT_INSTALLED_YET ->
            "The offline $languageName voice is not installed yet. Install its voice data " +
                "in Android text-to-speech settings, then restart Say it."

        TextToSpeech.ERROR_NETWORK,
        TextToSpeech.ERROR_NETWORK_TIMEOUT,
        -> "The selected $languageName voice requires a network connection. Install an offline " +
            "$languageName voice in Android text-to-speech settings."

        TextToSpeech.ERROR_SERVICE ->
            "Android text-to-speech service failed while playing $languageName. Verify that its " +
                "offline voice is installed, then restart or change the preferred TTS engine " +
                "in Android Settings."

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
