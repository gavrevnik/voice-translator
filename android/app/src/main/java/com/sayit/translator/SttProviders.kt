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

    override suspend fun start(
        language: AppLanguage,
        onPartialResult: (String) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error("System SpeechRecognizer is unavailable on this device.")
        }
        result = CompletableDeferred()
        this@SystemSttProvider.onPartialResult = onPartialResult
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
        return withTimeout(20_000) { result.await() }.trim().ifBlank {
            error("System SpeechRecognizer returned an empty transcript.")
        }
    }

    override fun cancel() {
        recognizer?.cancel()
        if (!result.isCompleted) result.cancel()
        onPartialResult = {}
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
                    IllegalStateException("System SpeechRecognizer error: ${errorLabel(error)}"),
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

    private fun errorLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "service"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        else -> "code $code"
    }
}

fun Context.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
