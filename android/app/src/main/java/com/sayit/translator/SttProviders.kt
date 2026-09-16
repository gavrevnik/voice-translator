package com.sayit.translator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers

interface SttProvider {
    suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit)
    suspend fun stop(): String
    fun cancel()
}

class SystemSttProvider(private val context: Context) : SttProvider {
    private var recognizer: SpeechRecognizer? = null
    private var result = CompletableDeferred<String>()
    private var onPartialResult: (String) -> Unit = {}
    private var activeLanguage: AppLanguage? = null

    override suspend fun start(
        language: AppLanguage,
        onPartialResult: (String) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error("System SpeechRecognizer is unavailable on this device.")
        }
        result = CompletableDeferred()
        this@SystemSttProvider.onPartialResult = onPartialResult
        activeLanguage = language
        val speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
            it.setRecognitionListener(listener)
        }
        speechRecognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.bcp47)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_600_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_600_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_600_000L)
            },
        )
    }

    override suspend fun stop(): String {
        withContext(Dispatchers.Main.immediate) { recognizer?.stopListening() }
        return try {
            withTimeout(20_000) { result.await() }.trim().ifBlank {
                error("Android speech recognition returned an empty transcript.")
            }
        } finally {
            activeLanguage = null
            onPartialResult = {}
        }
    }

    override fun cancel() {
        recognizer?.cancel()
        if (!result.isCompleted) result.cancel()
        onPartialResult = {}
        activeLanguage = null
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = bestResult(results)
            if (text.isNotBlank()) onPartialResult(text)
            if (!result.isCompleted) result.complete(text)
        }

        override fun onError(error: Int) {
            if (!result.isCompleted) {
                result.completeExceptionally(
                    IllegalStateException(systemSpeechRecognizerErrorMessage(error, activeLanguage)),
                )
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            bestResult(partialResults).takeIf(String::isNotBlank)?.let(onPartialResult)
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun bestResult(bundle: Bundle?): String = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()

}

internal fun systemSpeechRecognizerErrorMessage(code: Int, language: AppLanguage?): String {
    val languageName = language?.canonicalName ?: "selected language"
    return when (code) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "The selected Android speech recognition service does not support $languageName " +
                "(error 12). Install or enable an offline $languageName speech pack in Android " +
                "Settings, or use Groq Whisper when online."

        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "$languageName speech recognition is supported, but its offline speech pack is not " +
                "downloaded (error 13). Install it in Android voice input settings and try again."

        SpeechRecognizer.ERROR_AUDIO ->
            "Android speech recognition could not read microphone audio."

        SpeechRecognizer.ERROR_CLIENT ->
            "Android speech recognition could not start. Restart Say it and try again."

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required for speech recognition."

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "Offline Android speech recognition is unavailable for $languageName. Install its " +
            "offline speech pack, or use Groq Whisper when online."

        SpeechRecognizer.ERROR_NO_MATCH ->
            "No $languageName speech was recognized. Please try again."

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Android speech recognition is busy. Wait a moment and try again."

        SpeechRecognizer.ERROR_SERVER ->
            "Android speech recognition service failed. Restart it or choose another recognition " +
                "service in Android Settings."

        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech was detected. Please try again."

        else -> "Android speech recognition failed for $languageName (error $code)."
    }
}

fun Context.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
