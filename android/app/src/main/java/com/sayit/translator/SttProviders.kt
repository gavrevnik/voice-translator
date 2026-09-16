package com.sayit.translator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers

interface SttProvider {
    suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit)
    suspend fun stop(): String
    fun cancel()
}

class SystemSttProvider(private val context: Context) : SttProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var result = CompletableDeferred<String>()
    private var onPartialResult: (String) -> Unit = {}
    private var activeLanguage: AppLanguage? = null
    private var committedTranscript = ""
    private var currentPartial = ""
    private var keepListening = false
    private var sessionActive = false
    private val restartRunnable = Runnable { startRecognitionSession() }
    private val stopFallbackRunnable = Runnable { completeWithAccumulatedTranscript() }

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
        committedTranscript = ""
        currentPartial = ""
        keepListening = true
        sessionActive = false
        removeScheduledCallbacks()
        startRecognitionSession()
    }

    internal suspend fun offlineLanguageAvailability(language: AppLanguage): AndroidSttAvailability {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return AndroidSttAvailability.UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return AndroidSttAvailability.UNKNOWN
        }
        return withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MS) {
            checkRecognitionSupport(language)
        } ?: AndroidSttAvailability.UNKNOWN
    }

    internal fun recognitionServiceKey(): String {
        val configuredService = runCatching {
            Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE_SETTING)
        }.getOrNull()?.takeIf(String::isNotBlank)
        val voiceDetailsService = runCatching {
            RecognizerIntent.getVoiceDetailsIntent(context)
                ?.component
                ?.flattenToShortString()
        }.getOrNull()?.takeIf(String::isNotBlank)
        return configuredService ?: voiceDetailsService ?: DEFAULT_RECOGNITION_SERVICE_KEY
    }

    override suspend fun stop(): String {
        withContext(Dispatchers.Main.immediate) {
            keepListening = false
            removeScheduledCallbacks()
            if (sessionActive) {
                recognizer?.stopListening()
                mainHandler.postDelayed(stopFallbackRunnable, STOP_FALLBACK_MS)
            } else {
                completeWithAccumulatedTranscript()
            }
        }
        return try {
            withTimeout(20_000) { result.await() }.trim().ifBlank {
                error("Android speech recognition returned an empty transcript.")
            }
        } finally {
            removeScheduledCallbacks()
            activeLanguage = null
            onPartialResult = {}
            committedTranscript = ""
            currentPartial = ""
            sessionActive = false
        }
    }

    override fun cancel() {
        keepListening = false
        sessionActive = false
        removeScheduledCallbacks()
        recognizer?.cancel()
        if (!result.isCompleted) result.cancel()
        onPartialResult = {}
        activeLanguage = null
        committedTranscript = ""
        currentPartial = ""
    }

    fun destroy() {
        removeScheduledCallbacks()
        recognizer?.destroy()
        recognizer = null
        sessionActive = false
    }

    private fun ensureRecognizer(): SpeechRecognizer =
        recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
            it.setRecognitionListener(listener)
        }

    private fun startRecognitionSession() {
        if (!keepListening || result.isCompleted) return
        val language = activeLanguage ?: return
        runCatching {
            sessionActive = true
            ensureRecognizer().startListening(speechRecognitionIntent(language))
        }.onFailure { throwable ->
            sessionActive = false
            if (!result.isCompleted) result.completeExceptionally(throwable)
        }
    }

    private fun scheduleNextRecognitionSession(delayMs: Long = SESSION_RESTART_DELAY_MS) {
        sessionActive = false
        if (!keepListening || result.isCompleted) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun publishPartial(text: String) {
        currentPartial = text.trim()
        mergeRecognitionTranscripts(committedTranscript, currentPartial)
            .takeIf(String::isNotBlank)
            ?.let(onPartialResult)
    }

    private fun commitSegment(text: String) {
        val segment = text.trim().ifBlank { currentPartial }
        if (segment.isNotBlank()) {
            committedTranscript = mergeRecognitionTranscripts(committedTranscript, segment)
            onPartialResult(committedTranscript)
        }
        currentPartial = ""
    }

    private fun completeWithAccumulatedTranscript() {
        if (result.isCompleted) return
        sessionActive = false
        val transcript = mergeRecognitionTranscripts(committedTranscript, currentPartial)
        result.complete(transcript)
    }

    private fun removeScheduledCallbacks() {
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(stopFallbackRunnable)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkRecognitionSupport(
        language: AppLanguage,
    ): AndroidSttAvailability =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val speechRecognizer = ensureRecognizer()
                runCatching {
                    speechRecognizer.checkRecognitionSupport(
                        speechRecognitionIntent(language),
                        ContextCompat.getMainExecutor(context),
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                if (continuation.isActive) {
                                    continuation.resume(
                                        recognitionSupportAvailability(
                                            recognitionSupport = recognitionSupport,
                                            requestedLanguageTag = language.bcp47,
                                        ),
                                    )
                                }
                            }

                            override fun onError(error: Int) {
                                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) destroy()
                                if (continuation.isActive) {
                                    continuation.resume(recognitionSupportErrorAvailability(error))
                                }
                            }
                        },
                    )
                }.onFailure {
                    if (continuation.isActive) {
                        continuation.resume(AndroidSttAvailability.UNKNOWN)
                    }
                }
            }
        }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            commitSegment(bestResult(results))
            if (keepListening) {
                scheduleNextRecognitionSession()
            } else {
                completeWithAccumulatedTranscript()
            }
        }

        override fun onError(error: Int) {
            sessionActive = false
            if (keepListening && error in RESTARTABLE_SESSION_ERRORS) {
                scheduleNextRecognitionSession(
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        BUSY_RESTART_DELAY_MS
                    } else {
                        SESSION_RESTART_DELAY_MS
                    },
                )
                return
            }
            if (!result.isCompleted) {
                if (!keepListening && assembledTranscript().isNotBlank()) {
                    completeWithAccumulatedTranscript()
                    return
                }
                val failure = SystemSttException(
                    errorCode = error,
                    message = systemSpeechRecognizerErrorMessage(error, activeLanguage),
                )
                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) destroy()
                result.completeExceptionally(
                    failure,
                )
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            bestResult(partialResults).takeIf(String::isNotBlank)?.let(::publishPartial)
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun bestResult(bundle: Bundle?): String = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()

    private fun assembledTranscript(): String =
        mergeRecognitionTranscripts(committedTranscript, currentPartial)

    private companion object {
        const val SUPPORT_CHECK_TIMEOUT_MS = 2_500L
        const val SESSION_RESTART_DELAY_MS = 180L
        const val BUSY_RESTART_DELAY_MS = 500L
        const val STOP_FALLBACK_MS = 2_500L
        const val VOICE_RECOGNITION_SERVICE_SETTING = "voice_recognition_service"
        const val DEFAULT_RECOGNITION_SERVICE_KEY = "system-default"
        val RESTARTABLE_SESSION_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        )
    }

}

internal fun mergeRecognitionTranscripts(committed: String, incoming: String): String {
    val stable = committed.trim()
    val addition = incoming.trim()
    if (stable.isBlank()) return addition
    if (addition.isBlank()) return stable

    val stableWords = stable.split(Regex("\\s+"))
    val additionWords = addition.split(Regex("\\s+"))
    if (
        stableWords.size >= MIN_RECOGNITION_OVERLAP_WORDS &&
        normalizedRecognitionText(stable) == normalizedRecognitionText(addition)
    ) {
        return stable
    }

    val maximumOverlap = minOf(stableWords.size, additionWords.size)
    val overlap = (maximumOverlap downTo MIN_RECOGNITION_OVERLAP_WORDS).firstOrNull { size ->
        val stableSuffix = stableWords.takeLast(size).joinToString(" ")
        val additionPrefix = additionWords.take(size).joinToString(" ")
        normalizedRecognitionText(stableSuffix) == normalizedRecognitionText(additionPrefix)
    } ?: 0
    return (stableWords + additionWords.drop(overlap)).joinToString(" ").trim()
}

private fun normalizedRecognitionText(text: String): String = text
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private const val MIN_RECOGNITION_OVERLAP_WORDS = 2

internal class SystemSttException(
    val errorCode: Int,
    message: String,
) : IllegalStateException(message)

internal fun isMissingAndroidSpeechLanguage(throwable: Throwable): Boolean =
    (throwable as? SystemSttException)?.errorCode in setOf(
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    )

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun recognitionSupportAvailability(
    recognitionSupport: RecognitionSupport,
    requestedLanguageTag: String,
): AndroidSttAvailability {
    if (
        recognitionSupport.installedOnDeviceLanguages.any {
            languageTagMatches(it, requestedLanguageTag)
        }
    ) {
        return AndroidSttAvailability.AVAILABLE
    }
    val explicitlyUnavailable = sequenceOf(
        recognitionSupport.pendingOnDeviceLanguages,
        recognitionSupport.supportedOnDeviceLanguages,
        recognitionSupport.onlineLanguages,
    ).flatten().any { languageTagMatches(it, requestedLanguageTag) }
    return if (explicitlyUnavailable) {
        AndroidSttAvailability.UNAVAILABLE
    } else {
        AndroidSttAvailability.UNKNOWN
    }
}

private fun recognitionSupportErrorAvailability(error: Int): AndroidSttAvailability =
    if (
        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
    ) {
        AndroidSttAvailability.UNAVAILABLE
    } else {
        AndroidSttAvailability.UNKNOWN
    }

internal fun speechRecognitionIntent(language: AppLanguage): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.bcp47)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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

        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "Android speech recognition service disconnected (error 11). Say it will reconnect " +
                "on the next attempt. If it repeats, restart the Google or Samsung speech service."

        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech was detected. Please try again."

        else -> "Android speech recognition failed for $languageName (error $code)."
    }
}

fun Context.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
