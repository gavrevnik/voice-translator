package com.sayit.translator

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {
    private val apiKeyStore = ApiKeyStore(application)
    private val settings = AppSettings(application)
    private val diagnostics = TurnDiagnosticsRecorder(application)
    private val openAiTranslationProvider: TranslationProvider = OpenAiTranslationProvider()
    private val geminiTranslationProvider: TranslationProvider = GeminiTranslationProvider()
    private val offlineModelManager = OfflineModelManager(
        application,
        assetName = "offline_models.json",
        overrideDownloadUrl = BuildConfig.OFFLINE_OPUS_MODEL_URL,
        retiredModelIds = setOf("opus-mt-sla-sla-int8"),
    )
    private val offlineIneModelManager = OfflineModelManager(
        application,
        assetName = "offline_models_ine.json",
        overrideDownloadUrl = BuildConfig.OFFLINE_OPUS_INE_MODEL_URL,
        retiredModelIds = setOf("opus-mt-itc-itc-int8"),
    )
    private val offlineTranslationProvider = OfflineOpusTranslationProvider(
        application,
        offlineModelManager,
        OfflineOpusFamily.SLAVIC,
    )
    private val offlineIneTranslationProvider = OfflineOpusTranslationProvider(
        application,
        offlineIneModelManager,
        OfflineOpusFamily.INDO_EUROPEAN,
    )
    private val systemTtsProvider: TtsProvider = SystemTtsProvider(application)
    private val systemProvider = SystemSttProvider(application)
    private val groqProvider = GroqWhisperSttProvider()
    private val autoSttRouter = AutoSttRouter(application, systemProvider)
    private val whisperModelManager = WhisperModelManager(application)
    private val whisperProvider = WhisperSttProvider(whisperModelManager) { name, fields ->
        diagnostics.event(name, fields)
    }
    private var activeSttProvider: SttProvider? = null
    private var timerJob: Job? = null
    private var turnGeneration = 0L
    private var recordingStartedAtMs = 0L

    private val _uiState = MutableStateFlow(
        TranslatorUiState(
            languageA = settings.languageA,
            languageB = settings.languageB,
            sttEngine = settings.sttEngine,
            serbianScript = settings.serbianScript,
            offlineModelStatus = offlineModelManager.status.value,
            offlineModelDownloadSizeLabel = offlineModelManager.manifest.downloadSizeLabel,
            offlineIneModelStatus = offlineIneModelManager.status.value,
            offlineIneModelDownloadSizeLabel = offlineIneModelManager.manifest.downloadSizeLabel,
            offlineRuntimeAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
            whisperModelStatus = whisperModelManager.status.value,
            whisperModelDownloadSizeLabel = whisperModelManager.manifest.downloadSizeLabel,
            whisperRuntimeAvailable = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
            hasOpenAiApiKey = apiKeyStore.hasKey(),
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
            offlineIneModelManager.status.collect { modelStatus ->
                _uiState.update { it.copy(offlineIneModelStatus = modelStatus) }
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
    }

    fun tapMicrophone(side: LanguageSide) {
        val state = _uiState.value
        when {
            state.status == VoiceStatus.LISTENING && state.activeSide == side -> finishTurn(side)
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
                    GeminiTranslationModel.FLASH_3_1_LITE
                } else {
                    it.geminiModel
                },
                textA = "",
                textB = "",
                resultSide = null,
                activeSttEngine = null,
                status = if (offlineBecameUnavailable) VoiceStatus.ERROR else VoiceStatus.READY,
                error = if (offlineBecameUnavailable) {
                    offlinePairMessage(current.translationEngine)
                } else {
                    null
                },
            )
        }
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
                model = option.openAiModel ?: it.model,
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

    fun downloadOfflineIneModel() {
        if (_uiState.value.offlineIneModelStatus is OfflineModelStatus.Downloading) return
        viewModelScope.launch { offlineIneModelManager.downloadAndInstall() }
    }

    fun deleteOfflineIneModel() {
        viewModelScope.launch {
            offlineIneTranslationProvider.close()
            offlineIneModelManager.deleteModel()
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
        _uiState.update { it.copy(sttEngine = engine, activeSttEngine = null, error = null) }
        if (
            engine.isWhisperOffline() &&
            _uiState.value.whisperModelStatus is OfflineModelStatus.Installed
        ) {
            prepareWhisperInBackground()
        }
    }

    fun saveApiKey(apiKey: String): Boolean = runCatching {
        apiKeyStore.save(apiKey)
        _uiState.update { it.copy(hasOpenAiApiKey = true, error = null) }
    }.fold(
        onSuccess = { true },
        onFailure = { throwable ->
            showError(throwable)
            false
        },
    )

    fun deleteApiKey() {
        apiKeyStore.clear()
        _uiState.update { it.copy(hasOpenAiApiKey = false) }
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
        if (state.translationEngine == TranslationEngine.OPENAI && !state.hasOpenAiApiKey) {
            showError(IllegalStateException("Add an OpenAI API key in settings first."))
            return
        }
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
                "source_side" to side.name.lowercase(),
                "source_language" to language.canonicalName,
                "source_code" to language.code,
                "target_language" to targetLanguage.canonicalName,
                "target_code" to targetLanguage.code,
                "translation_engine" to state.translationEngine.name.lowercase(),
                "translation_model" to TranslationOption.from(state).label,
                "serbian_script" to state.serbianScript.name.lowercase(),
                "playback_engine" to "android_system_tts",
            ),
        )
        val generation = ++turnGeneration
        systemTtsProvider.stop()
        _uiState.update {
            it.copy(
                status = VoiceStatus.RECOGNIZING,
                activeSide = side,
                activeSttEngine = null,
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
                val resolvedEngine = if (state.sttEngine == SttEngine.AUTO) {
                    autoSttRouter.resolve(language)
                } else {
                    state.sttEngine
                }
                if (generation != turnGeneration) return@launch
                if (state.sttEngine == SttEngine.AUTO) {
                    Log.i(
                        TIMING_TAG,
                        "event=auto_stt_resolved language=${language.code} " +
                            "engine=${resolvedEngine.name.lowercase()}",
                    )
                }
                diagnostics.event(
                    "stt_engine_resolved",
                    mapOf(
                        "requested" to state.sttEngine.name.lowercase(),
                        "resolved" to resolvedEngine.name.lowercase(),
                        "language" to language.code,
                    ),
                )
                _uiState.update { it.copy(activeSttEngine = resolvedEngine) }
                validateSttEngine(resolvedEngine)
                if (resolvedEngine.isWhisperOffline()) {
                    whisperProvider.prepare()
                }
                val provider = sttProviderFor(resolvedEngine)
                activeSttProvider = provider
                recordingStartedAtMs = SystemClock.elapsedRealtime()
                var firstPartialLogged = false
                _uiState.update { it.copy(status = VoiceStatus.LISTENING) }
                startTimer()
                provider.start(language, onPartialResult = partialResult@{ partial ->
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
                })
                diagnostics.event(
                    "recording_started",
                    mapOf("stt_engine" to resolvedEngine.name.lowercase()),
                )
            }.onFailure {
                if (generation == turnGeneration) {
                    activeSttProvider = null
                    showError(it)
                }
            }
        }
    }

    private fun finishTurn(sourceSide: LanguageSide) {
        timerJob?.cancel()
        val snapshot = _uiState.value
        val sourceLanguage = if (sourceSide == LanguageSide.A) snapshot.languageA else snapshot.languageB
        val targetLanguage = if (sourceSide == LanguageSide.A) snapshot.languageB else snapshot.languageA
        val targetSide = if (sourceSide == LanguageSide.A) LanguageSide.B else LanguageSide.A
        val provider = activeSttProvider ?: return
        val generation = turnGeneration

        viewModelScope.launch {
            try {
                val stoppedAt = SystemClock.elapsedRealtime()
                diagnostics.event(
                    "stop_tapped",
                    mapOf(
                        "recording_duration_ms" to
                            (stoppedAt - recordingStartedAtMs).coerceAtLeast(0L).toString(),
                    ),
                )
                _uiState.update { it.copy(status = VoiceStatus.RECOGNIZING) }
                val transcript = provider.stop().trim()
                logTiming("stt_final_after_stop_tap", SystemClock.elapsedRealtime() - stoppedAt)
                diagnostics.event(
                    "recognition_completed",
                    mapOf("transcript_characters" to transcript.length.toString()),
                )
                if (generation != turnGeneration) return@launch
                activeSttProvider = null
                if (snapshot.activeSttEngine == SttEngine.SYSTEM) {
                    autoSttRouter.recordAndroidSuccess(sourceLanguage)
                }
                _uiState.update {
                    if (sourceSide == LanguageSide.A) {
                        it.copy(textA = transcript, partialTranscriptSide = null)
                    } else {
                        it.copy(textB = transcript, partialTranscriptSide = null)
                    }
                }

                val translationProvider = when (snapshot.translationEngine) {
                    TranslationEngine.OPENAI -> openAiTranslationProvider
                    TranslationEngine.GEMINI -> geminiTranslationProvider
                    TranslationEngine.OFFLINE_OPUS_SLAVIC -> offlineTranslationProvider
                    TranslationEngine.OFFLINE_OPUS_INDO_EUROPEAN -> offlineIneTranslationProvider
                }
                val apiKey = when (snapshot.translationEngine) {
                    TranslationEngine.OPENAI -> apiKeyStore.load()
                        ?: error("The saved OpenAI API key could not be read. Save it again in settings.")
                    TranslationEngine.GEMINI -> BuildConfig.GEMINI_API_KEY
                    TranslationEngine.OFFLINE_OPUS_SLAVIC -> ""
                    TranslationEngine.OFFLINE_OPUS_INDO_EUROPEAN -> ""
                }
                _uiState.update { it.copy(status = VoiceStatus.TRANSLATING) }
                val translationStartedAt = SystemClock.elapsedRealtime()
                val translated = translationProvider.translate(
                    apiKey = apiKey,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    transcript = transcript,
                    model = snapshot.model,
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
                    logTiming("playback_start", SystemClock.elapsedRealtime() - playbackRequestedAt)
                    logTiming("stop_tap_to_playback_start", SystemClock.elapsedRealtime() - stoppedAt)
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
                    if (snapshot.activeSttEngine == SttEngine.SYSTEM) {
                        autoSttRouter.recordAndroidFailure(sourceLanguage, throwable)
                    }
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
            SttEngine.AUTO -> error("Auto speech recognition did not resolve an engine.")
            SttEngine.SYSTEM -> Unit
            SttEngine.GROQ -> if (BuildConfig.GROQ_API_KEY.isBlank()) {
                error("Groq API key is not configured in this build.")
            }
            SttEngine.WHISPER_OFFLINE,
            SttEngine.WHISPER_OFFLINE_LIVE,
            -> {
                if (!state.whisperRuntimeAvailable) error(WHISPER_RUNTIME_MESSAGE)
                if (state.whisperModelStatus !is OfflineModelStatus.Installed) {
                    val message = if (state.sttEngine == SttEngine.AUTO) {
                        "No Android offline speech pack or usable internet connection was found. " +
                            "Download the Whisper Offline model in Settings first."
                    } else {
                        "Download the Whisper Offline model in Settings first."
                    }
                    error(message)
                }
            }
        }
    }

    private fun sttProviderFor(engine: SttEngine): SttProvider = when (engine) {
        SttEngine.AUTO -> error("Auto speech recognition did not resolve an engine.")
        SttEngine.SYSTEM -> systemProvider
        SttEngine.GROQ -> groqProvider
        SttEngine.WHISPER_OFFLINE -> whisperProvider.also {
            it.setLivePartialsEnabled(false)
        }
        SttEngine.WHISPER_OFFLINE_LIVE -> whisperProvider.also {
            it.setLivePartialsEnabled(true)
        }
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
        activeSttProvider?.cancel()
        systemProvider.destroy()
        groqProvider.cancel()
        systemTtsProvider.release()
        runBlocking { whisperProvider.release() }
        offlineTranslationProvider.close()
        offlineIneTranslationProvider.close()
        super.onCleared()
    }

    private fun logTiming(stage: String, durationMs: Long) {
        Log.i(TIMING_TAG, "stage=$stage duration_ms=$durationMs")
        diagnostics.event(stage, mapOf("duration_ms" to durationMs.toString()))
    }

    private fun offlineModelStatus(
        state: TranslatorUiState,
        engine: TranslationEngine,
    ): OfflineModelStatus = when (engine) {
        TranslationEngine.OFFLINE_OPUS_SLAVIC -> state.offlineModelStatus
        TranslationEngine.OFFLINE_OPUS_INDO_EUROPEAN -> state.offlineIneModelStatus
        else -> error("Not an offline OPUS engine: $engine")
    }

    private fun offlinePairMessage(engine: TranslationEngine): String = when (engine) {
        TranslationEngine.OFFLINE_OPUS_SLAVIC ->
            "OPUS Slavic FP32 supports Russian ↔ Serbian or Croatian only. " +
                "Choose a cloud model for this pair."
        TranslationEngine.OFFLINE_OPUS_INDO_EUROPEAN ->
            "OPUS Indo-European FP32 supports Russian ↔ Romanian or Spanish only. " +
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
