package com.sayit.translator

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
                    IllegalStateException("System text-to-speech playback failed."),
                )
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == activeUtteranceId) activeUtterance?.completeExceptionally(
                    IllegalStateException("System text-to-speech error: $errorCode"),
                )
            }
        })
    }

    override suspend fun speak(text: String, language: AppLanguage, onStarted: (() -> Unit)?) {
        ready.await()
        stop()
        val locale = Locale.forLanguageTag(language.bcp47)
        if (textToSpeech.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
            error("Install the ${language.nativeName} voice in Samsung text-to-speech settings.")
        }
        textToSpeech.language = locale
        val utterance = CompletableDeferred<Unit>()
        val utteranceId = UUID.randomUUID().toString()
        activeUtterance = utterance
        activeUtteranceId = utteranceId
        activeOnStarted = onStarted
        val status = textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            utteranceId,
        )
        if (status == TextToSpeech.ERROR) error("System text-to-speech rejected playback.")
        utterance.await()
        activeUtterance = null
        activeUtteranceId = null
        activeOnStarted = null
    }

    override fun stop() {
        textToSpeech.stop()
        activeUtterance?.complete(Unit)
        activeUtterance = null
        activeUtteranceId = null
        activeOnStarted = null
    }

    override fun release() {
        stop()
        textToSpeech.shutdown()
    }
}
