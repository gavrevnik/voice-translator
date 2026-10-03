package com.sayit.translator

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale
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
    private var recognizerComponent: ComponentName? = null
    private var activeRecognitionServiceName = "Android"
    private val installedServices = mutableMapOf<AppLanguage, RecognitionServiceCandidate>()
    private val downloadServices = mutableMapOf<AppLanguage, RecognitionServiceCandidate>()
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
        val service = preferredInstalledService(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && service == null) {
            val support = inspectOfflineLanguage(language)
            if (
                support.availability in setOf(
                    AndroidLanguagePackAvailability.DOWNLOADABLE,
                    AndroidLanguagePackAvailability.DOWNLOADING,
                    AndroidLanguagePackAvailability.SCHEDULED,
                    AndroidLanguagePackAvailability.ONLINE_ONLY,
                    AndroidLanguagePackAvailability.UNSUPPORTED,
                )
            ) {
                error(
                    "Offline ${language.canonicalName} speech is not installed in Samsung or " +
                        "Google. Use the download link below the progress bar.",
                )
            }
        }
        configureRecognizer(service)
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

    internal suspend fun inspectOfflineLanguage(
        language: AppLanguage,
    ): AndroidLanguagePackStatus {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNSUPPORTED,
                detail = "Android speech recognition is unavailable.",
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNKNOWN,
                providerName = "Android",
                detail = "Android cannot report installed speech packs on this OS version.",
            )
        }
        val services = recognitionServices()
        if (services.isEmpty()) {
            return AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNKNOWN,
                providerName = "Android",
                detail = "Android did not expose its installed speech recognition services.",
            )
        }
        val checks = services.map { candidate ->
            candidate to checkRecognitionSupport(candidate, language)
        }
        checks.firstOrNull { (_, status) -> status.isInstalled }?.let { (candidate, status) ->
            installedServices[language] = candidate
            downloadServices.remove(language)
            return status
        }
        installedServices.remove(language)
        checks.firstOrNull { (_, status) -> status.canDownload }?.let { (candidate, status) ->
            downloadServices[language] = candidate
            return status
        }
        checks.firstOrNull { (_, status) ->
            status.availability in setOf(
                AndroidLanguagePackAvailability.DOWNLOADING,
                AndroidLanguagePackAvailability.SCHEDULED,
            )
        }?.let { (candidate, status) ->
            downloadServices[language] = candidate
            return status
        }
        downloadServices.remove(language)
        return checks.firstOrNull { (_, status) ->
            status.availability == AndroidLanguagePackAvailability.ONLINE_ONLY
        }?.second
            ?: checks.firstOrNull { (_, status) ->
                status.availability == AndroidLanguagePackAvailability.UNKNOWN
            }?.second
            ?: checks.firstOrNull { (_, status) ->
                status.availability == AndroidLanguagePackAvailability.ERROR
            }?.second
            ?: AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNSUPPORTED,
                detail = "No installed Samsung or Google recognizer supports ${language.bcp47}.",
            )
    }

    internal suspend fun downloadOfflineLanguage(
        language: AppLanguage,
        onProgress: (AndroidLanguagePackStatus) -> Unit,
    ): AndroidLanguagePackStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNKNOWN,
                providerName = "Android",
                detail = "Open Android voice input settings to install this language.",
            )
        }
        val current = inspectOfflineLanguage(language)
        if (current.isInstalled) return current
        if (
            current.availability == AndroidLanguagePackAvailability.SCHEDULED ||
            current.availability == AndroidLanguagePackAvailability.DOWNLOADING
        ) {
            return current
        }
        val candidate = downloadServices[language] ?: return current
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            triggerModelDownloadWithProgress(candidate, language, onProgress)
        } else {
            triggerModelDownload(candidate, language)
        }
        if (requested.isInstalled) {
            installedServices[language] = candidate
            downloadServices.remove(language)
        }
        return requested
    }

    internal fun activeServiceName(): String = activeRecognitionServiceName

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
        recognizerComponent = null
        activeRecognitionServiceName = "Android"
        sessionActive = false
    }

    private fun configureRecognizer(candidate: RecognitionServiceCandidate?) {
        if (recognizer != null && recognizerComponent == candidate?.component) return
        recognizer?.destroy()
        recognizer = null
        recognizerComponent = candidate?.component
        activeRecognitionServiceName = candidate?.providerName ?: "Android"
    }

    private fun ensureRecognizer(): SpeechRecognizer = recognizer
        ?: (recognizerComponent?.let { component ->
            SpeechRecognizer.createSpeechRecognizer(context, component)
        } ?: SpeechRecognizer.createSpeechRecognizer(context)).also {
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

    private suspend fun preferredInstalledService(
        language: AppLanguage,
    ): RecognitionServiceCandidate? = installedServices[language] ?: run {
        inspectOfflineLanguage(language)
        installedServices[language]
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkRecognitionSupport(
        candidate: RecognitionServiceCandidate,
        language: AppLanguage,
    ): AndroidLanguagePackStatus = withContext(Dispatchers.Main.immediate) {
        val speechRecognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(context, candidate.component)
        }.getOrElse { throwable ->
            return@withContext AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.ERROR,
                providerName = candidate.providerName,
                detail = throwable.message ?: "Could not start the recognition service.",
            )
        }
        try {
            withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        speechRecognizer.checkRecognitionSupport(
                            speechRecognitionIntent(language),
                            ContextCompat.getMainExecutor(context),
                            object : RecognitionSupportCallback {
                                override fun onSupportResult(
                                    recognitionSupport: RecognitionSupport,
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            recognitionSupportPackStatus(
                                                recognitionSupport = recognitionSupport,
                                                requestedLanguageTag = language.bcp47,
                                                providerName = candidate.providerName,
                                            ),
                                        )
                                    }
                                }

                                override fun onError(error: Int) {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            recognitionSupportErrorPackStatus(
                                                error = error,
                                                providerName = candidate.providerName,
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }.onFailure { throwable ->
                        if (continuation.isActive) {
                            continuation.resume(
                                AndroidLanguagePackStatus(
                                    availability = AndroidLanguagePackAvailability.ERROR,
                                    providerName = candidate.providerName,
                                    detail = throwable.message ?: "Speech support check failed.",
                                ),
                            )
                        }
                    }
                }
            } ?: AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.UNKNOWN,
                providerName = candidate.providerName,
                detail = "The speech service did not answer the support check.",
            )
        } finally {
            speechRecognizer.destroy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun triggerModelDownload(
        candidate: RecognitionServiceCandidate,
        language: AppLanguage,
    ): AndroidLanguagePackStatus = withContext(Dispatchers.Main.immediate) {
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, candidate.component)
        try {
            speechRecognizer.triggerModelDownload(speechRecognitionIntent(language))
            AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.SCHEDULED,
                providerName = candidate.providerName,
                detail = "The speech pack download was requested.",
            )
        } catch (throwable: Throwable) {
            AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.ERROR,
                providerName = candidate.providerName,
                detail = throwable.message ?: "Could not request the speech pack download.",
            )
        } finally {
            speechRecognizer.destroy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun triggerModelDownloadWithProgress(
        candidate: RecognitionServiceCandidate,
        language: AppLanguage,
        onProgress: (AndroidLanguagePackStatus) -> Unit,
    ): AndroidLanguagePackStatus = withContext(Dispatchers.Main.immediate) {
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, candidate.component)
        try {
            withTimeoutOrNull(MODEL_DOWNLOAD_REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        mainHandler.post { speechRecognizer.destroy() }
                    }
                    runCatching {
                        speechRecognizer.triggerModelDownload(
                            speechRecognitionIntent(language),
                            ContextCompat.getMainExecutor(context),
                            object : ModelDownloadListener {
                                override fun onProgress(completedPercent: Int) {
                                    onProgress(
                                        AndroidLanguagePackStatus(
                                            availability =
                                                AndroidLanguagePackAvailability.DOWNLOADING,
                                            providerName = candidate.providerName,
                                            progressPercent = completedPercent.coerceIn(0, 100),
                                        ),
                                    )
                                }

                                override fun onScheduled() {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            AndroidLanguagePackStatus(
                                                availability =
                                                    AndroidLanguagePackAvailability.SCHEDULED,
                                                providerName = candidate.providerName,
                                                detail =
                                                    "The speech pack download was scheduled.",
                                            ),
                                        )
                                    }
                                }

                                override fun onSuccess() {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            AndroidLanguagePackStatus(
                                                availability =
                                                    AndroidLanguagePackAvailability.INSTALLED,
                                                providerName = candidate.providerName,
                                            ),
                                        )
                                    }
                                }

                                override fun onError(error: Int) {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            AndroidLanguagePackStatus(
                                                availability =
                                                    AndroidLanguagePackAvailability.ERROR,
                                                providerName = candidate.providerName,
                                                detail =
                                                    "Speech pack download failed (error $error).",
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }.onFailure { throwable ->
                        if (continuation.isActive) {
                            continuation.resume(
                                AndroidLanguagePackStatus(
                                    availability = AndroidLanguagePackAvailability.ERROR,
                                    providerName = candidate.providerName,
                                    detail = throwable.message
                                        ?: "Could not request the speech pack download.",
                                ),
                            )
                        }
                    }
                }
            } ?: AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.ERROR,
                providerName = candidate.providerName,
                detail = "The speech service did not answer the download request.",
            )
        } finally {
            speechRecognizer.destroy()
        }
    }

    @Suppress("DEPRECATION")
    private fun recognitionServices(): List<RecognitionServiceCandidate> = context.packageManager
        .queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.MATCH_ALL,
        )
        .asSequence()
        .mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            if (!serviceInfo.enabled || !serviceInfo.exported) return@mapNotNull null
            val packageName = serviceInfo.packageName
            if (!isSamsungOrGoogleSpeechProvider(packageName)) return@mapNotNull null
            RecognitionServiceCandidate(
                component = ComponentName(packageName, serviceInfo.name),
                packageName = packageName,
                providerName = androidSpeechProviderName(
                    packageName,
                    resolveInfo.loadLabel(context.packageManager)?.toString().orEmpty(),
                ),
            )
        }
        .distinctBy { it.component }
        .sortedWith(
            compareBy<RecognitionServiceCandidate> {
                androidSpeechProviderPriority(it.packageName)
            }.thenBy { it.providerName },
        )
        .toList()

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
        const val MODEL_DOWNLOAD_REQUEST_TIMEOUT_MS = 30_000L
        const val SESSION_RESTART_DELAY_MS = 180L
        const val BUSY_RESTART_DELAY_MS = 500L
        const val STOP_FALLBACK_MS = 2_500L
        val RESTARTABLE_SESSION_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        )
    }

}

private data class RecognitionServiceCandidate(
    val component: ComponentName,
    val packageName: String,
    val providerName: String,
)

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

internal fun languageTagMatches(candidate: String, requested: String): Boolean {
    val candidateLocale = Locale.forLanguageTag(candidate.replace('_', '-'))
    val requestedLocale = Locale.forLanguageTag(requested.replace('_', '-'))
    if (candidateLocale.language.isBlank() || requestedLocale.language.isBlank()) return false
    return candidateLocale.language.equals(requestedLocale.language, ignoreCase = true)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun recognitionSupportPackStatus(
    recognitionSupport: RecognitionSupport,
    requestedLanguageTag: String,
    providerName: String,
): AndroidLanguagePackStatus {
    if (
        recognitionSupport.installedOnDeviceLanguages.any {
            languageTagMatches(it, requestedLanguageTag)
        }
    ) {
        return AndroidLanguagePackStatus(
            availability = AndroidLanguagePackAvailability.INSTALLED,
            providerName = providerName,
        )
    }
    if (
        recognitionSupport.pendingOnDeviceLanguages.any {
            languageTagMatches(it, requestedLanguageTag)
        }
    ) {
        return AndroidLanguagePackStatus(
            availability = AndroidLanguagePackAvailability.SCHEDULED,
            providerName = providerName,
            detail = "The offline speech pack is scheduled for download.",
        )
    }
    if (
        recognitionSupport.supportedOnDeviceLanguages.any {
            languageTagMatches(it, requestedLanguageTag)
        }
    ) {
        return AndroidLanguagePackStatus(
            availability = AndroidLanguagePackAvailability.DOWNLOADABLE,
            providerName = providerName,
        )
    }
    if (
        recognitionSupport.onlineLanguages.any {
            languageTagMatches(it, requestedLanguageTag)
        }
    ) {
        return AndroidLanguagePackStatus(
            availability = AndroidLanguagePackAvailability.ONLINE_ONLY,
            providerName = providerName,
            detail = "Only online recognition is available from $providerName.",
        )
    }
    return AndroidLanguagePackStatus(
        availability = AndroidLanguagePackAvailability.UNSUPPORTED,
        providerName = providerName,
    )
}

private fun recognitionSupportErrorPackStatus(
    error: Int,
    providerName: String,
): AndroidLanguagePackStatus = when (error) {
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> AndroidLanguagePackStatus(
        availability = AndroidLanguagePackAvailability.UNSUPPORTED,
        providerName = providerName,
    )
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> AndroidLanguagePackStatus(
        availability = AndroidLanguagePackAvailability.DOWNLOADABLE,
        providerName = providerName,
    )
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> AndroidLanguagePackStatus(
        availability = AndroidLanguagePackAvailability.UNKNOWN,
        providerName = providerName,
        detail = "$providerName cannot report installed offline languages.",
    )
    else -> AndroidLanguagePackStatus(
        availability = AndroidLanguagePackAvailability.ERROR,
        providerName = providerName,
        detail = "Speech support check failed (error $error).",
    )
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
