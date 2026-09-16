package com.sayit.translator

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val diagnostics = TurnDiagnosticsRecorder(application)
    private val geminiTranslationProvider: TranslationProvider = GeminiTranslationProvider()
    private val offlineModelManager = OfflineModelManager(
        application,
        assetName = "offline_models.json",
        overrideDownloadUrl = BuildConfig.OFFLINE_OPUS_MODEL_URL,
        retiredModelIds = setOf(
            "opus-mt-sla-sla-int8",
            "opus-mt-ine-ine-fp32",
            "opus-mt-itc-itc-int8",
        ),
    )
    private val offlineTranslationProvider = OfflineOpusTranslationProvider(
        application,
        offlineModelManager,
        OfflineOpusFamily.SLAVIC,
    )
    private val systemTtsProvider = SystemTtsProvider(application)
    private val systemProvider = SystemSttProvider(application)
    private val groqProvider = GroqWhisperSttProvider(diagnosticEvent = { name, fields ->
        diagnostics.event(name, fields)
    })
    private val whisperModelManager = WhisperModelManager(application)
    private val whisperProvider = WhisperSttProvider(whisperModelManager) { name, fields ->
        diagnostics.event(name, fields)
    }
    private var activeSttProvider: SttProvider? = null
    private var timerJob: Job? = null
    private var languagePackJob: Job? = null
    private var languagePackGeneration = 0L
    private var turnGeneration = 0L
    private var recordingStartedAtMs = 0L

    private val _uiState = MutableStateFlow(
        TranslatorUiState(
            languageA = settings.languageA,
            languageB = settings.languageB,
            layoutMode = settings.layoutMode,
            sttEngine = settings.sttEngine,
            serbianScript = settings.serbianScript,
            silenceAutoStopSeconds = settings.silenceAutoStopSeconds,
            offlineModelStatus = offlineModelManager.status.value,
            offlineModelDownloadSizeLabel = offlineModelManager.manifest.downloadSizeLabel,
            offlineRuntimeAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
            whisperModelStatus = whisperModelManager.status.value,
            whisperModelDownloadSizeLabel = whisperModelManager.manifest.downloadSizeLabel,
            whisperRuntimeAvailable = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
            hasLastDiagnostics = diagnostics.hasLastCycle(),
        ),
    )
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            offlineModelManager.status.collect { modelStatus ->
                _uiState.update { it.copy(offlineModelStatus = modelStatus) }
            }
        }
        viewModelScope.launch {
            whisperModelManager.status.collect { modelStatus ->
                _uiState.update { it.copy(whisperModelStatus = modelStatus) }
                if (
                    modelStatus is OfflineModelStatus.Installed &&
                    _uiState.value.sttEngine.isWhisperOffline()
                ) {
                    prepareWhisperInBackground()
                }
            }
        }
        refreshAndroidLanguagePacks()
    }

    fun tapMicrophone(side: LanguageSide) {
        val state = _uiState.value
        when {
            state.status == VoiceStatus.LISTENING && state.activeSide == side ->
                finishTurn(side, StopTrigger.MANUAL)
            state.status == VoiceStatus.READY || state.status == VoiceStatus.ERROR -> startTurn(side)
        }
    }

    fun setLanguage(side: LanguageSide, language: AppLanguage) {
        val current = _uiState.value
        if (current.status != VoiceStatus.READY && current.status != VoiceStatus.ERROR) return
        val nextA = if (side == LanguageSide.A) {
            language
        } else if (language == current.languageA) {
            current.languageB
        } else {
            current.languageA
        }
        val nextB = if (side == LanguageSide.B) {
            language
        } else if (language == current.languageB) {
            current.languageA
        } else {
            current.languageB
        }
        settings.languageA = nextA
        settings.languageB = nextB
        val offlineBecameUnavailable =
            current.translationEngine.isOfflineOpus() &&
                !isOfflineOpusDirection(current.translationEngine, nextA, nextB)
        _uiState.update {
            it.copy(
                languageA = nextA,
                languageB = nextB,
                translationEngine = if (offlineBecameUnavailable) {
                    TranslationEngine.GEMINI
                } else {
                    it.translationEngine
                },
                geminiModel = if (offlineBecameUnavailable) {
                    GeminiTranslationModel.FLASH_3_5_LITE
                } else {
                    it.geminiModel
                },
                textA = "",
                textB = "",
                resultSide = null,
                status = if (offlineBecameUnavailable) VoiceStatus.ERROR else VoiceStatus.READY,
                error = if (offlineBecameUnavailable) {
                    offlinePairMessage(current.translationEngine)
                } else {
                    null
                },
            )
        }
        refreshAndroidLanguagePacks()
    }

    fun setTranslationOption(option: TranslationOption) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        val state = _uiState.value
        if (option.engine.isOfflineOpus() && !state.offlineRuntimeAvailable) {
            _uiState.update { it.copy(status = VoiceStatus.ERROR, error = OFFLINE_RUNTIME_MESSAGE) }
            return
        }
        if (
            option.engine.isOfflineOpus() &&
            !isOfflineOpusDirection(option.engine, state.languageA, state.languageB)
        ) {
            _uiState.update {
                it.copy(status = VoiceStatus.ERROR, error = offlinePairMessage(option.engine))
            }
            return
        }
        _uiState.update {
            it.copy(
                translationEngine = option.engine,
                geminiModel = option.geminiModel ?: it.geminiModel,
                error = null,
            )
        }
    }

    fun setSerbianScript(script: SerbianScript) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        settings.serbianScript = script
        _uiState.update { it.copy(serbianScript = script, status = VoiceStatus.READY, error = null) }
    }

    fun downloadOfflineModel() {
        if (_uiState.value.offlineModelStatus is OfflineModelStatus.Downloading) return
        viewModelScope.launch { offlineModelManager.downloadAndInstall() }
    }

    fun deleteOfflineModel() {
        viewModelScope.launch {
            offlineTranslationProvider.close()
            offlineModelManager.deleteModel()
        }
    }

    fun downloadWhisperModel() {
        if (_uiState.value.whisperModelStatus is OfflineModelStatus.Downloading) return
        viewModelScope.launch { whisperModelManager.downloadAndInstall() }
    }

    fun deleteWhisperModel() {
        viewModelScope.launch {
            whisperProvider.release()
            whisperModelManager.deleteModel()
        }
    }

    fun setSttEngine(engine: SttEngine) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        if (engine.isWhisperOffline() && !_uiState.value.whisperRuntimeAvailable) {
            _uiState.update { it.copy(status = VoiceStatus.ERROR, error = WHISPER_RUNTIME_MESSAGE) }
            return
        }
        settings.sttEngine = engine
        _uiState.update { it.copy(sttEngine = engine, error = null) }
        refreshAndroidLanguagePacks()
        if (
            engine.isWhisperOffline() &&
            _uiState.value.whisperModelStatus is OfflineModelStatus.Installed
        ) {
            prepareWhisperInBackground()
        }
    }

    fun setSilenceAutoStopSeconds(seconds: Int) {
        val validated = seconds.coerceIn(
            MIN_SILENCE_AUTO_STOP_SECONDS,
            MAX_SILENCE_AUTO_STOP_SECONDS,
        )
        settings.silenceAutoStopSeconds = validated
        _uiState.update { it.copy(silenceAutoStopSeconds = validated) }
    }

    fun setLayoutMode(mode: LayoutMode) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        settings.layoutMode = mode
        _uiState.update { it.copy(layoutMode = mode) }
    }

    fun toggleLayoutMode() {
        val next = when (_uiState.value.layoutMode) {
            LayoutMode.SINGLE -> LayoutMode.CONVERSATION
            LayoutMode.CONVERSATION -> LayoutMode.SINGLE
        }
        setLayoutMode(next)
    }

    fun refreshAndroidLanguagePacks() {
        languagePackJob?.cancel()
        val generation = ++languagePackGeneration
        val languages = AppLanguage.entries.toSet()
        _uiState.update { state ->
            state.copy(
                androidSttLanguagePacks =
                    languages.associateWith { AndroidLanguagePackStatus.CHECKING },
                androidTtsLanguagePacks =
                    languages.associateWith { AndroidLanguagePackStatus.CHECKING },
            )
        }
        languagePackJob = viewModelScope.launch {
            val sttChecks = languages.associateWith { language ->
                async {
                    runCatching { systemProvider.inspectOfflineLanguage(language) }
                        .getOrElse { throwable -> androidPackCheckFailure("Android", throwable) }
                }
            }
            val ttsChecks = languages.associateWith { language ->
                async {
                    runCatching { systemTtsProvider.inspectOfflineLanguage(language) }
                        .getOrElse { throwable -> androidPackCheckFailure("Android", throwable) }
                }
            }
            val sttResults = sttChecks.mapValues { (_, deferred) -> deferred.await() }
            val ttsResults = ttsChecks.mapValues { (_, deferred) -> deferred.await() }
            if (generation != languagePackGeneration) return@launch
            _uiState.update { state ->
                state.copy(
                    androidSttLanguagePacks = sttResults,
                    androidTtsLanguagePacks = ttsResults,
                )
            }
        }
    }

    fun downloadAndroidSpeechPack(language: AppLanguage) {
        val state = _uiState.value
        if (language !in AppLanguage.entries) return
        updateAndroidSttPack(
            language,
            AndroidLanguagePackStatus(
                availability = AndroidLanguagePackAvailability.DOWNLOADING,
                providerName = state.androidSttLanguagePacks[language]?.providerName.orEmpty(),
                progressPercent = 0,
            ),
        )
        viewModelScope.launch {
            val result = runCatching {
                systemProvider.downloadOfflineLanguage(language) { progress ->
                    updateAndroidSttPack(language, progress)
                }
            }.getOrElse { throwable -> androidPackCheckFailure("Android", throwable) }
            updateAndroidSttPack(language, result)
            if (result.isInstalled) refreshAndroidLanguagePacks()
        }
    }

    fun createAndroidTtsInstallIntent(language: AppLanguage): Intent? {
        if (language !in AppLanguage.entries) return null
        return runCatching { systemTtsProvider.installVoiceDataIntent(language) }.getOrNull()
    }

    fun createDiagnosticsShareIntent(): Intent? = runCatching {
        diagnostics.createShareIntent()
    }.onFailure {
        Log.e(TIMING_TAG, "Could not prepare the last-cycle diagnostics export.", it)
    }.getOrNull()

    fun replay() {
        val state = _uiState.value
        val side = state.resultSide ?: return
        if (state.status != VoiceStatus.READY) return
        val text = if (side == LanguageSide.A) state.textA else state.textB
        val language = if (side == LanguageSide.A) state.languageA else state.languageB
        val generation = ++turnGeneration
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(status = VoiceStatus.SPEAKING, error = null) }
                val requestedAt = SystemClock.elapsedRealtime()
                systemTtsProvider.speak(text, language) {
                    logTiming("playback_start", SystemClock.elapsedRealtime() - requestedAt)
                }
                logTiming("playback_total", SystemClock.elapsedRealtime() - requestedAt)
                if (generation == turnGeneration) {
                    _uiState.update { it.copy(status = VoiceStatus.READY) }
                }
            }.onFailure { if (generation == turnGeneration) showError(it) }
        }
    }

    fun stopPlayback() {
        if (_uiState.value.status != VoiceStatus.SPEAKING) return
        turnGeneration += 1
        systemTtsProvider.stop()
        activeSttProvider = null
        _uiState.update {
            it.copy(status = VoiceStatus.READY, activeSide = null, elapsedSeconds = 0)
        }
        Log.i(TIMING_TAG, "event=playback_stopped_by_user")
        diagnostics.event("playback_stopped_by_user")
        diagnostics.finish("playback_stopped")
    }

    fun reportPermissionDenied() {
        showError(IllegalStateException("Microphone permission is required."))
    }

    private fun startTurn(side: LanguageSide) {
        val state = _uiState.value
        if (state.translationEngine == TranslationEngine.GEMINI && BuildConfig.GEMINI_API_KEY.isBlank()) {
            showError(IllegalStateException("Gemini API key is not configured in this build."))
            return
        }
        if (state.translationEngine.isOfflineOpus()) {
            if (!state.offlineRuntimeAvailable) {
                showError(IllegalStateException(OFFLINE_RUNTIME_MESSAGE))
                return
            }
            if (
                !isOfflineOpusDirection(
                    state.translationEngine,
                    state.languageA,
                    state.languageB,
                )
            ) {
                showError(IllegalStateException(offlinePairMessage(state.translationEngine)))
                return
            }
            if (offlineModelStatus(state, state.translationEngine) !is OfflineModelStatus.Installed) {
                showError(
                    IllegalStateException(
                        "Download the selected offline OPUS model in Settings first.",
                    ),
                )
                return
            }
        }
        val language = if (side == LanguageSide.A) state.languageA else state.languageB
        val targetLanguage = if (side == LanguageSide.A) state.languageB else state.languageA
        diagnostics.begin(
            mapOf(
                "requested_stt" to state.sttEngine.name.lowercase(),
                "whisper_model" to WHISPER_OFFLINE_MODEL,
                "source_side" to side.name.lowercase(),
                "source_language" to language.canonicalName,
                "source_code" to language.code,
                "target_language" to targetLanguage.canonicalName,
                "target_code" to targetLanguage.code,
                "translation_engine" to state.translationEngine.name.lowercase(),
                "translation_model" to TranslationOption.from(state).label,
                "serbian_script" to state.serbianScript.name.lowercase(),
                "playback_engine" to "android_system_tts",
                "silence_auto_stop_seconds" to state.silenceAutoStopSeconds.toString(),
            ),
        )
        val generation = ++turnGeneration
        systemTtsProvider.stop()
        _uiState.update {
            it.copy(
                status = VoiceStatus.RECOGNIZING,
                activeSide = side,
                partialTranscriptSide = null,
                textA = "",
                textB = "",
                resultSide = null,
                elapsedSeconds = 0,
                error = null,
                hasLastDiagnostics = true,
            )
        }
        viewModelScope.launch {
            runCatching {
                if (generation != turnGeneration) return@launch
                diagnostics.event(
                    "stt_engine_selected",
                    mapOf(
                        "engine" to state.sttEngine.name.lowercase(),
                        "language" to language.code,
                    ),
                )
                validateSttEngine(state.sttEngine)
                if (state.sttEngine.isWhisperOffline()) {
                    whisperProvider.prepare()
                }
                val provider = sttProviderFor(state.sttEngine)
                activeSttProvider = provider
                recordingStartedAtMs = SystemClock.elapsedRealtime()
                var firstPartialLogged = false
                _uiState.update { it.copy(status = VoiceStatus.LISTENING) }
                startTimer()
                val partialResultHandler: (String) -> Unit = partialResult@{ partial ->
                    if (generation != turnGeneration || partial.isBlank()) return@partialResult
                    if (!firstPartialLogged) {
                        firstPartialLogged = true
                        logTiming(
                            "stt_first_partial_from_recording_start",
                            SystemClock.elapsedRealtime() - recordingStartedAtMs,
                        )
                    }
                    _uiState.update { current ->
                        if (current.status != VoiceStatus.LISTENING || current.activeSide != side) {
                            current
                        } else if (side == LanguageSide.A) {
                            current.copy(textA = partial, partialTranscriptSide = side)
                        } else {
                            current.copy(textB = partial, partialTranscriptSide = side)
                        }
                    }
                }
                if (provider === groqProvider) {
                    groqProvider.start(
                        language = language,
                        silenceAutoStopSeconds = state.silenceAutoStopSeconds,
                        onSilenceAutoStop = {
                            viewModelScope.launch {
                                if (generation == turnGeneration) {
                                    finishTurn(side, StopTrigger.SILENCE)
                                }
                            }
                        },
                    )
                } else {
                    provider.start(language, onPartialResult = partialResultHandler)
                }
                if (state.sttEngine == SttEngine.SYSTEM) {
                    diagnostics.event(
                        "android_stt_service_selected",
                        mapOf("provider" to systemProvider.activeServiceName()),
                    )
                }
                diagnostics.event(
                    "recording_started",
                    mapOf("stt_engine" to state.sttEngine.name.lowercase()),
                )
            }.onFailure {
                if (generation == turnGeneration) {
                    activeSttProvider = null
                    showError(it)
                }
            }
        }
    }

    private fun finishTurn(sourceSide: LanguageSide, stopTrigger: StopTrigger) {
        val snapshot = _uiState.value
        if (
            snapshot.status != VoiceStatus.LISTENING ||
            snapshot.activeSide != sourceSide
        ) {
            return
        }
        val sourceLanguage = if (sourceSide == LanguageSide.A) snapshot.languageA else snapshot.languageB
        val targetLanguage = if (sourceSide == LanguageSide.A) snapshot.languageB else snapshot.languageA
        val targetSide = if (sourceSide == LanguageSide.A) LanguageSide.B else LanguageSide.A
        val provider = activeSttProvider ?: return
        val generation = turnGeneration
        timerJob?.cancel()
        _uiState.update { it.copy(status = VoiceStatus.RECOGNIZING) }

        viewModelScope.launch {
            try {
                val stoppedAt = SystemClock.elapsedRealtime()
                diagnostics.event(
                    stopTrigger.eventName,
                    mapOf(
                        "recording_duration_ms" to
                            (stoppedAt - recordingStartedAtMs).coerceAtLeast(0L).toString(),
                    ),
                )
                val transcript = provider.stop().trim()
                logTiming(stopTrigger.finalTimingStage, SystemClock.elapsedRealtime() - stoppedAt)
                diagnostics.event(
                    "recognition_completed",
                    mapOf("transcript_characters" to transcript.length.toString()),
                )
                if (generation != turnGeneration) return@launch
                activeSttProvider = null
                _uiState.update {
                    if (sourceSide == LanguageSide.A) {
                        it.copy(textA = transcript, partialTranscriptSide = null)
                    } else {
                        it.copy(textB = transcript, partialTranscriptSide = null)
                    }
                }

                val translationProvider = when (snapshot.translationEngine) {
                    TranslationEngine.GEMINI -> geminiTranslationProvider
                    TranslationEngine.OFFLINE_OPUS_SLAVIC -> offlineTranslationProvider
                }
                val apiKey = when (snapshot.translationEngine) {
                    TranslationEngine.GEMINI -> BuildConfig.GEMINI_API_KEY
                    TranslationEngine.OFFLINE_OPUS_SLAVIC -> ""
                }
                _uiState.update { it.copy(status = VoiceStatus.TRANSLATING) }
                val translationStartedAt = SystemClock.elapsedRealtime()
                val translated = translationProvider.translate(
                    apiKey = apiKey,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    transcript = transcript,
                    geminiModel = snapshot.geminiModel,
                    serbianScript = snapshot.serbianScript,
                )
                logTiming(
                    "translation_${snapshot.translationEngine.name.lowercase()}",
                    SystemClock.elapsedRealtime() - translationStartedAt,
                )
                diagnostics.event(
                    "translation_completed",
                    mapOf("translation_characters" to translated.translatedText.length.toString()),
                )
                if (generation != turnGeneration) return@launch
                _uiState.update {
                    if (targetSide == LanguageSide.A) {
                        it.copy(textA = translated.translatedText, resultSide = targetSide)
                    } else {
                        it.copy(textB = translated.translatedText, resultSide = targetSide)
                    }
                }

                _uiState.update { it.copy(status = VoiceStatus.SPEAKING) }
                val playbackRequestedAt = SystemClock.elapsedRealtime()
                diagnostics.event(
                    "playback_requested",
                    mapOf("language" to targetLanguage.code),
                )
                systemTtsProvider.speak(translated.translatedText, targetLanguage) {
                    diagnostics.event(
                        "android_tts_service_selected",
                        mapOf("provider" to systemTtsProvider.activeServiceName()),
                    )
                    logTiming("playback_start", SystemClock.elapsedRealtime() - playbackRequestedAt)
                    logTiming(
                        stopTrigger.playbackTimingStage,
                        SystemClock.elapsedRealtime() - stoppedAt,
                    )
                }
                logTiming("playback_total", SystemClock.elapsedRealtime() - playbackRequestedAt)
                if (generation == turnGeneration) {
                    _uiState.update {
                        it.copy(status = VoiceStatus.READY, activeSide = null, elapsedSeconds = 0)
                    }
                    diagnostics.finish("completed")
                }
            } catch (throwable: Throwable) {
                provider.cancel()
                if (generation == turnGeneration) {
                    activeSttProvider = null
                    showError(throwable)
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                _uiState.update { state ->
                    if (state.status == VoiceStatus.LISTENING) {
                        state.copy(elapsedSeconds = state.elapsedSeconds + 1)
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun validateSttEngine(engine: SttEngine) {
        val state = _uiState.value
        when (engine) {
            SttEngine.SYSTEM -> Unit
            SttEngine.GROQ -> if (BuildConfig.GROQ_API_KEY.isBlank()) {
                error("Groq API key is not configured in this build.")
            }
            SttEngine.WHISPER_OFFLINE -> {
                if (!state.whisperRuntimeAvailable) error(WHISPER_RUNTIME_MESSAGE)
                if (state.whisperModelStatus !is OfflineModelStatus.Installed) {
                    error("Download the Whisper Offline model in Settings first.")
                }
            }
        }
    }

    private fun sttProviderFor(engine: SttEngine): SttProvider = when (engine) {
        SttEngine.SYSTEM -> systemProvider
        SttEngine.GROQ -> groqProvider
        SttEngine.WHISPER_OFFLINE -> whisperProvider
    }

    private fun showError(throwable: Throwable) {
        timerJob?.cancel()
        diagnostics.fail(throwable)
        _uiState.update {
            it.copy(
                status = VoiceStatus.ERROR,
                activeSide = null,
                partialTranscriptSide = null,
                elapsedSeconds = 0,
                error = throwable.message ?: "Something went wrong.",
            )
        }
    }

    private fun prepareWhisperInBackground() {
        viewModelScope.launch {
            runCatching { whisperProvider.prepare() }
                .onFailure { Log.w(TIMING_TAG, "Whisper model preloading failed.", it) }
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        languagePackJob?.cancel()
        activeSttProvider?.cancel()
        systemProvider.destroy()
        groqProvider.cancel()
        systemTtsProvider.release()
        runBlocking { whisperProvider.release() }
        offlineTranslationProvider.close()
        super.onCleared()
    }

    private fun logTiming(stage: String, durationMs: Long) {
        Log.i(TIMING_TAG, "stage=$stage duration_ms=$durationMs")
        diagnostics.event(stage, mapOf("duration_ms" to durationMs.toString()))
    }

    private fun updateAndroidSttPack(
        language: AppLanguage,
        status: AndroidLanguagePackStatus,
    ) {
        _uiState.update { state ->
            if (language !in AppLanguage.entries) {
                state
            } else {
                state.copy(
                    androidSttLanguagePacks = state.androidSttLanguagePacks + (language to status),
                )
            }
        }
    }

    private fun androidPackCheckFailure(
        providerName: String,
        throwable: Throwable,
    ): AndroidLanguagePackStatus = AndroidLanguagePackStatus(
        availability = AndroidLanguagePackAvailability.ERROR,
        providerName = providerName,
        detail = throwable.message ?: "Android language pack check failed.",
    )

    private fun offlineModelStatus(
        state: TranslatorUiState,
        engine: TranslationEngine,
    ): OfflineModelStatus = when (engine) {
        TranslationEngine.OFFLINE_OPUS_SLAVIC -> state.offlineModelStatus
        else -> error("Not an offline OPUS engine: $engine")
    }

    private fun offlinePairMessage(engine: TranslationEngine): String = when (engine) {
        TranslationEngine.OFFLINE_OPUS_SLAVIC ->
            "OPUS Slavic FP32 supports Russian ↔ Serbian or Croatian only. " +
                "Choose a cloud model for this pair."
        else -> "The selected offline OPUS model does not support this language pair."
    }

    private companion object {
        const val TIMING_TAG = "SayItTiming"
        const val OFFLINE_RUNTIME_MESSAGE =
            "Offline OPUS requires an arm64 phone running Android 9 or newer."
        const val WHISPER_RUNTIME_MESSAGE =
            "Whisper Offline requires a 64-bit ARM Android phone."
    }
}

private enum class StopTrigger(
    val eventName: String,
    val finalTimingStage: String,
    val playbackTimingStage: String,
) {
    MANUAL(
        eventName = "stop_tapped",
        finalTimingStage = "stt_final_after_stop_tap",
        playbackTimingStage = "stop_tap_to_playback_start",
    ),
    SILENCE(
        eventName = "silence_auto_stop",
        finalTimingStage = "stt_final_after_silence",
        playbackTimingStage = "silence_stop_to_playback_start",
    ),
}
