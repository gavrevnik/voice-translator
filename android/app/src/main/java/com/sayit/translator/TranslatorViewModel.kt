package com.sayit.translator

import android.app.Application
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

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {
    private val apiKeyStore = ApiKeyStore(application)
    private val settings = AppSettings(application)
    private val openAiTranslationProvider: TranslationProvider = OpenAiTranslationProvider()
    private val geminiTranslationProvider: TranslationProvider = GeminiTranslationProvider()
    private val systemTtsProvider: TtsProvider = SystemTtsProvider(application)
    private val geminiTtsProvider: TtsProvider = GeminiTtsProvider(BuildConfig.GEMINI_API_KEY)
    private val systemProvider = SystemSttProvider(application)
    private val groqProvider = GroqWhisperSttProvider()
    private var activeSttProvider: SttProvider? = null
    private var timerJob: Job? = null
    private var turnGeneration = 0L
    private var recordingStartedAtMs = 0L

    private val _uiState = MutableStateFlow(
        TranslatorUiState(
            languageA = settings.languageA,
            languageB = settings.languageB,
            model = settings.model,
            geminiModel = settings.geminiModel,
            translationEngine = settings.translationEngine,
            ttsEngine = settings.ttsEngine,
            sttEngine = settings.sttEngine,
            hasOpenAiApiKey = apiKeyStore.hasKey(),
        ),
    )
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

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
        _uiState.update {
            it.copy(
                languageA = nextA,
                languageB = nextB,
                textA = "",
                textB = "",
                resultSide = null,
                status = VoiceStatus.READY,
                error = null,
            )
        }
    }

    fun setTranslationOption(option: TranslationOption) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        settings.translationEngine = option.engine
        option.openAiModel?.let { settings.model = it }
        option.geminiModel?.let { settings.geminiModel = it }
        _uiState.update {
            it.copy(
                translationEngine = option.engine,
                model = option.openAiModel ?: it.model,
                geminiModel = option.geminiModel ?: it.geminiModel,
                error = null,
            )
        }
    }

    fun setTtsEngine(engine: TtsEngine) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        systemTtsProvider.stop()
        geminiTtsProvider.stop()
        settings.ttsEngine = engine
        _uiState.update { it.copy(ttsEngine = engine, error = null) }
    }

    fun setSttEngine(engine: SttEngine) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        settings.sttEngine = engine
        _uiState.update { it.copy(sttEngine = engine, error = null) }
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

    fun replay() {
        val state = _uiState.value
        val side = state.resultSide ?: return
        if (state.status != VoiceStatus.READY) return
        val text = if (side == LanguageSide.A) state.textA else state.textB
        val language = if (side == LanguageSide.A) state.languageA else state.languageB
        val ttsProvider = ttsProviderFor(state.ttsEngine)
        val generation = ++turnGeneration
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(status = VoiceStatus.SPEAKING, error = null) }
                val requestedAt = SystemClock.elapsedRealtime()
                ttsProvider.speak(text, language) {
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
        geminiTtsProvider.stop()
        activeSttProvider = null
        _uiState.update {
            it.copy(status = VoiceStatus.READY, activeSide = null, elapsedSeconds = 0)
        }
        Log.i(TIMING_TAG, "event=playback_stopped_by_user")
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
        if (state.ttsEngine == TtsEngine.GEMINI && BuildConfig.GEMINI_API_KEY.isBlank()) {
            showError(IllegalStateException("Gemini API key is required for Gemini TTS."))
            return
        }
        val language = if (side == LanguageSide.A) state.languageA else state.languageB
        val generation = ++turnGeneration
        val provider = when (state.sttEngine) {
            SttEngine.SYSTEM -> systemProvider
            SttEngine.GROQ -> groqProvider
        }
        systemTtsProvider.stop()
        geminiTtsProvider.stop()
        activeSttProvider = provider
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        var firstPartialLogged = false
        _uiState.update {
            it.copy(
                status = VoiceStatus.LISTENING,
                activeSide = side,
                textA = "",
                textB = "",
                resultSide = null,
                elapsedSeconds = 0,
                error = null,
            )
        }
        startTimer()
        viewModelScope.launch {
            runCatching {
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
                            current.copy(textA = partial)
                        } else {
                            current.copy(textB = partial)
                        }
                    }
                })
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
                _uiState.update { it.copy(status = VoiceStatus.RECOGNIZING) }
                val transcript = provider.stop().trim()
                logTiming("stt_final_after_stop_tap", SystemClock.elapsedRealtime() - stoppedAt)
                if (generation != turnGeneration) return@launch
                activeSttProvider = null
                _uiState.update {
                    if (sourceSide == LanguageSide.A) it.copy(textA = transcript)
                    else it.copy(textB = transcript)
                }

                val translationProvider = when (snapshot.translationEngine) {
                    TranslationEngine.OPENAI -> openAiTranslationProvider
                    TranslationEngine.GEMINI -> geminiTranslationProvider
                }
                val apiKey = when (snapshot.translationEngine) {
                    TranslationEngine.OPENAI -> apiKeyStore.load()
                        ?: error("The saved OpenAI API key could not be read. Save it again in settings.")
                    TranslationEngine.GEMINI -> BuildConfig.GEMINI_API_KEY
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
                )
                logTiming(
                    "translation_${snapshot.translationEngine.name.lowercase()}",
                    SystemClock.elapsedRealtime() - translationStartedAt,
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
                val ttsProvider = ttsProviderFor(snapshot.ttsEngine)
                val playbackRequestedAt = SystemClock.elapsedRealtime()
                ttsProvider.speak(translated.translatedText, targetLanguage) {
                    logTiming("playback_start", SystemClock.elapsedRealtime() - playbackRequestedAt)
                    logTiming("stop_tap_to_playback_start", SystemClock.elapsedRealtime() - stoppedAt)
                }
                logTiming("playback_total", SystemClock.elapsedRealtime() - playbackRequestedAt)
                if (generation == turnGeneration) {
                    _uiState.update {
                        it.copy(status = VoiceStatus.READY, activeSide = null, elapsedSeconds = 0)
                    }
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

    private fun showError(throwable: Throwable) {
        timerJob?.cancel()
        _uiState.update {
            it.copy(
                status = VoiceStatus.ERROR,
                activeSide = null,
                elapsedSeconds = 0,
                error = throwable.message ?: "Something went wrong.",
            )
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        activeSttProvider?.cancel()
        systemProvider.destroy()
        groqProvider.cancel()
        systemTtsProvider.release()
        geminiTtsProvider.release()
        super.onCleared()
    }

    private fun ttsProviderFor(engine: TtsEngine): TtsProvider = when (engine) {
        TtsEngine.SYSTEM -> systemTtsProvider
        TtsEngine.GEMINI -> geminiTtsProvider
    }

    private fun logTiming(stage: String, durationMs: Long) {
        Log.i(TIMING_TAG, "stage=$stage duration_ms=$durationMs")
    }

    private companion object {
        const val TIMING_TAG = "SayItTiming"
    }
}
