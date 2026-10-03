package com.sayit.translator

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val diagnostics = TurnDiagnosticsRecorder(application)
    private val geminiTranslationProvider = GeminiTranslationProvider(
        diagnosticEvent = { name, fields -> diagnostics.event(name, fields) },
    )
    private val systemTtsProvider = SystemTtsProvider(application)
    private val geminiLiveTranscribeProvider =
        GeminiLiveTranscribeSttProvider(diagnosticEvent = { name, fields ->
            diagnostics.event(name, fields)
        })
    private val pairLanguageClassifier = PairLanguageClassifier()
    private var activeSttProvider: SttProvider? = null
    private var timerJob: Job? = null
    private var liveJob: Job? = null
    private var liveTurnStopSignal: CompletableDeferred<LiveStopTrigger>? = null
    private var languagePackJob: Job? = null
    private var languagePackGeneration = 0L
    private var turnGeneration = 0L
    private var recordingStartedAtMs = 0L
    private var livePreviousSpeaker: LanguageSide? = null

    private val _uiState = MutableStateFlow(
        TranslatorUiState(
            languageA = settings.languageA,
            languageB = settings.languageB,
            hasLastDiagnostics = diagnostics.hasLastCycle(),
        ),
    )
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    init {
        refreshAndroidLanguagePacks()
    }

    fun tapMicrophone(side: LanguageSide) {
        val state = _uiState.value
        if (state.liveModeActive) return
        when {
            state.status == VoiceStatus.LISTENING && state.activeSide == side ->
                finishTurn(side, StopTrigger.MANUAL)
            state.status == VoiceStatus.READY || state.status == VoiceStatus.ERROR -> startTurn(side)
        }
    }

    fun toggleLiveMode(initialSpeakerSide: LanguageSide) {
        if (_uiState.value.liveModeActive) {
            stopLiveMode()
        } else {
            startLiveMode(initialSpeakerSide)
        }
    }

    fun finishCurrentLiveTurn() {
        val state = _uiState.value
        if (!state.liveModeActive || state.status != VoiceStatus.LISTENING) return
        liveTurnStopSignal?.complete(LiveStopTrigger.MANUAL)
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
        refreshAndroidLanguagePacks()
    }

    fun setTranslationOption(option: TranslationOption) {
        if (_uiState.value.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        _uiState.update {
            it.copy(
                translationEngine = option.engine,
                geminiModel = option.geminiModel,
                error = null,
            )
        }
    }

    fun refreshAndroidLanguagePacks() {
        languagePackJob?.cancel()
        val generation = ++languagePackGeneration
        val languages = AppLanguage.entries.toSet()
        _uiState.update { state ->
            state.copy(
                androidTtsLanguagePacks =
                    languages.associateWith { AndroidLanguagePackStatus.CHECKING },
            )
        }
        languagePackJob = viewModelScope.launch {
            val ttsChecks = languages.associateWith { language ->
                async {
                    runCatching { systemTtsProvider.inspectOfflineLanguage(language) }
                        .getOrElse { throwable -> androidPackCheckFailure("Android", throwable) }
                }
            }
            val ttsResults = ttsChecks.mapValues { (_, deferred) -> deferred.await() }
            if (generation != languagePackGeneration) return@launch
            _uiState.update { state ->
                state.copy(
                    androidTtsLanguagePacks = ttsResults,
                )
            }
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
        if (_uiState.value.liveModeActive) {
            stopLiveMode()
            return
        }
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
        if (state.liveModeActive) return
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            showError(IllegalStateException("Gemini API key is not configured in this build."))
            return
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
                "playback_engine" to "android_system_tts",
            ) + endpointingDiagnosticFields(),
        )
        val generation = ++turnGeneration
        systemTtsProvider.stop()
        _uiState.update {
            it.copy(
                status = VoiceStatus.RECOGNIZING,
                liveModeActive = false,
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
                val provider = geminiLiveTranscribeProvider
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
                val onSilenceAutoStop: (StopTrigger) -> Unit = { stopTrigger ->
                    viewModelScope.launch {
                        if (generation == turnGeneration) {
                            finishTurn(side, stopTrigger)
                        }
                    }
                }
                geminiLiveTranscribeProvider.start(
                    language = language,
                    onServerVadEnd = { onSilenceAutoStop(StopTrigger.SERVER_VAD) },
                    onLocalRmsFallback = {
                        onSilenceAutoStop(StopTrigger.LOCAL_RMS_FALLBACK)
                    },
                    onPartialResult = partialResultHandler,
                )
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

                _uiState.update { it.copy(status = VoiceStatus.TRANSLATING) }
                val translationStartedAt = SystemClock.elapsedRealtime()
                val translated = geminiTranslationProvider.translate(
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    transcript = transcript,
                    geminiModel = snapshot.geminiModel,
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

    private fun startLiveMode(initialSpeakerSide: LanguageSide) {
        val state = _uiState.value
        if (state.status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR)) return
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            showError(IllegalStateException("Gemini API key is not configured in this build."))
            return
        }

        val generation = ++turnGeneration
        livePreviousSpeaker = initialSpeakerSide
        systemTtsProvider.stop()
        val initialSourceLanguage = state.languageFor(initialSpeakerSide)
        val initialTargetLanguage = state.languageFor(initialSpeakerSide.otherSide())
        diagnostics.beginLiveSession(
            mapOf(
                "mode" to "conversation_live",
                "language_a" to state.languageA.canonicalName,
                "language_b" to state.languageB.canonicalName,
                "initial_fallback_source_side" to initialSpeakerSide.name.lowercase(),
                "initial_fallback_source_language" to initialSourceLanguage.canonicalName,
                "initial_fallback_target_language" to initialTargetLanguage.canonicalName,
                "requested_stt" to state.sttEngine.name.lowercase(),
                "stt_model" to GEMINI_TRANSCRIBE_LIVE_MODEL,
                "translation_engine" to "gemini",
                "translation_model" to state.geminiModel.id,
                "playback_engine" to "android_system_tts",
            ) + endpointingDiagnosticFields(),
        )
        _uiState.update {
            it.copy(
                status = VoiceStatus.RECOGNIZING,
                liveModeActive = true,
                activeSide = null,
                partialTranscriptSide = null,
                textA = "",
                textB = "",
                resultSide = null,
                elapsedSeconds = 0,
                error = null,
                hasLastDiagnostics = true,
            )
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            try {
                while (generation == turnGeneration && _uiState.value.liveModeActive) {
                    runLiveCycle(generation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                activeSttProvider?.cancel()
                if (generation == turnGeneration) {
                    activeSttProvider = null
                    showError(throwable)
                }
            }
        }
    }

    private suspend fun runLiveCycle(generation: Long) {
        val snapshot = _uiState.value
        if (generation != turnGeneration || !snapshot.liveModeActive) return
        val fallbackSide = livePreviousSpeaker ?: LanguageSide.B
        val fallbackLanguage = if (fallbackSide == LanguageSide.A) {
            snapshot.languageA
        } else {
            snapshot.languageB
        }
        val fallbackTarget = if (fallbackSide == LanguageSide.A) {
            snapshot.languageB
        } else {
            snapshot.languageA
        }
        diagnostics.beginLiveCycle(
            mapOf(
                "fallback_source_side" to fallbackSide.name.lowercase(),
                "fallback_source_language" to fallbackLanguage.canonicalName,
                "fallback_target_language" to fallbackTarget.canonicalName,
            ),
        )
        val stopSignal = CompletableDeferred<LiveStopTrigger>()
        liveTurnStopSignal = stopSignal
        val liveProvider = geminiLiveTranscribeProvider
        activeSttProvider = liveProvider
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                status = VoiceStatus.LISTENING,
                activeSide = null,
                partialTranscriptSide = null,
                elapsedSeconds = 0,
                error = null,
                hasLastDiagnostics = true,
            )
        }
        startTimer()
        geminiLiveTranscribeProvider.startLive(
            languageA = snapshot.languageA,
            languageB = snapshot.languageB,
            onServerVadEnd = { stopSignal.complete(LiveStopTrigger.SERVER_VAD) },
            onLocalRmsFallback = {
                stopSignal.complete(LiveStopTrigger.LOCAL_RMS_FALLBACK)
            },
            onPartialResult = partial@{ partial ->
                if (generation != turnGeneration || partial.isBlank()) return@partial
                diagnostics.event(
                    "recognition_partial",
                    mapOf(
                        "stt_engine" to snapshot.sttEngine.name.lowercase(),
                        "text" to partial,
                        "text_characters" to partial.length.toString(),
                        "display_side_hint" to fallbackSide.name.lowercase(),
                    ),
                )
                _uiState.update { current ->
                    if (fallbackSide == LanguageSide.A) {
                        current.copy(textA = partial, textB = "", partialTranscriptSide = fallbackSide)
                    } else {
                        current.copy(textA = "", textB = partial, partialTranscriptSide = fallbackSide)
                    }
                }
            },
        )
        diagnostics.event(
            "recording_started",
            mapOf(
                "stt_engine" to snapshot.sttEngine.name.lowercase(),
                "language" to
                    listOf(snapshot.languageA.bcp47, snapshot.languageB.bcp47).joinToString(","),
            ),
        )
        val stopTrigger = try {
            stopSignal.await()
        } finally {
            if (liveTurnStopSignal === stopSignal) liveTurnStopSignal = null
        }
        if (generation != turnGeneration) return

        timerJob?.cancel()
        _uiState.update { it.copy(status = VoiceStatus.RECOGNIZING) }
        val stoppedAt = SystemClock.elapsedRealtime()
        diagnostics.event(
            stopTrigger.eventName,
            mapOf(
                "recording_duration_ms" to
                    (stoppedAt - recordingStartedAtMs).coerceAtLeast(0L).toString(),
            ),
        )
        val transcript = geminiLiveTranscribeProvider.stop()
        activeSttProvider = null
        logTiming(
            stopTrigger.finalTimingStage,
            SystemClock.elapsedRealtime() - stoppedAt,
        )
        if (generation != turnGeneration) return

        diagnostics.event(
            "recognition_final",
            mapOf(
                "stt_engine" to snapshot.sttEngine.name.lowercase(),
                "text" to transcript,
                "text_characters" to transcript.length.toString(),
                "reported_language" to "not_provided_by_gemini_live",
                "language_candidates" to
                    listOf(snapshot.languageA.bcp47, snapshot.languageB.bcp47).joinToString(","),
            ) + pairLanguageDiagnosticFeatures(transcript),
        )

        _uiState.update { it.copy(status = VoiceStatus.TRANSLATING) }
        val translationStartedAt = SystemClock.elapsedRealtime()
        val route = run {
            diagnostics.event(
                "pair_classification_started",
                mapOf(
                    "reason" to "gemini_live_text_only",
                    "candidate_a" to snapshot.languageA.bcp47,
                    "candidate_b" to snapshot.languageB.bcp47,
                    "text" to transcript,
                ) + pairLanguageDiagnosticFeatures(transcript),
            )
            val pairDecision = pairLanguageClassifier.classify(
                text = transcript,
                languageA = snapshot.languageA,
                languageB = snapshot.languageB,
            )
            val sourceLanguage = pairDecision.language
            val sourceSide = if (sourceLanguage == snapshot.languageA) {
                LanguageSide.A
            } else {
                LanguageSide.B
            }
            val targetLanguage = snapshot.languageFor(sourceSide.otherSide())
            diagnostics.event(
                "live_language_resolved",
                mapOf(
                    "detected_language" to "local_pair_classifier",
                    "mapped_language" to sourceLanguage.canonicalName,
                    "source_side" to sourceSide.name.lowercase(),
                    "target_language" to targetLanguage.code,
                    "resolution_method" to pairDecision.method,
                    "classifier_evidence" to pairDecision.evidence,
                    "pair_score_a" to pairDecision.scoreA.toString(),
                    "pair_score_b" to pairDecision.scoreB.toString(),
                    "language_candidates" to
                        listOf(snapshot.languageA.bcp47, snapshot.languageB.bcp47).joinToString(","),
                ),
            )
            _uiState.update { current ->
                if (sourceSide == LanguageSide.A) {
                    current.copy(
                        textA = transcript,
                        textB = "",
                        resultSide = null,
                        partialTranscriptSide = null,
                    )
                } else {
                    current.copy(
                        textA = "",
                        textB = transcript,
                        resultSide = null,
                        partialTranscriptSide = null,
                    )
                }
            }
            diagnostics.event(
                "translation_requested",
                mapOf(
                    "prompt_mode" to "standard_directional",
                    "source_language" to sourceLanguage.canonicalName,
                    "source_code" to sourceLanguage.code,
                    "target_language" to targetLanguage.canonicalName,
                    "target_code" to targetLanguage.code,
                    "input_text" to transcript,
                ),
            )
            val translation = geminiTranslationProvider.translate(
                apiKey = BuildConfig.GEMINI_API_KEY,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                transcript = transcript,
                geminiModel = snapshot.geminiModel,
            )
            LiveTranslationRoute(sourceSide, translation)
        }
        val sourceSide = route.sourceSide
        val targetSide = sourceSide.otherSide()
        val translated = route.translation
        val targetLanguage = translated.targetLanguage
        livePreviousSpeaker = sourceSide
        logTiming(
            "translation_gemini_live",
            SystemClock.elapsedRealtime() - translationStartedAt,
        )
        diagnostics.event(
            "translation_completed",
            mapOf(
                "source_language" to translated.sourceLanguage.canonicalName,
                "source_code" to translated.sourceLanguage.code,
                "target_language" to translated.targetLanguage.canonicalName,
                "target_code" to translated.targetLanguage.code,
                "translated_text" to translated.translatedText,
                "translation_characters" to translated.translatedText.length.toString(),
            ),
        )
        if (generation != turnGeneration) return
        _uiState.update { current ->
            if (targetSide == LanguageSide.A) {
                current.copy(textA = translated.translatedText, resultSide = targetSide)
            } else {
                current.copy(textB = translated.translatedText, resultSide = targetSide)
            }
        }

        _uiState.update { it.copy(status = VoiceStatus.SPEAKING) }
        val playbackRequestedAt = SystemClock.elapsedRealtime()
        diagnostics.event(
            "playback_requested",
            mapOf(
                "language" to targetLanguage.canonicalName,
                "language_code" to targetLanguage.code,
                "locale" to targetLanguage.bcp47,
                "text" to translated.translatedText,
            ),
        )
        systemTtsProvider.speak(translated.translatedText, targetLanguage) {
            diagnostics.event(
                "android_tts_service_selected",
                mapOf(
                    "provider" to systemTtsProvider.activeServiceName(),
                    "requested_language" to targetLanguage.canonicalName,
                    "requested_locale" to targetLanguage.bcp47,
                    "voice_name" to systemTtsProvider.activeVoiceName(),
                    "voice_locale" to systemTtsProvider.activeVoiceLocaleTag(),
                ),
            )
            logTiming("playback_start", SystemClock.elapsedRealtime() - playbackRequestedAt)
            logTiming(
                stopTrigger.playbackTimingStage,
                SystemClock.elapsedRealtime() - stoppedAt,
            )
        }
        logTiming("playback_total", SystemClock.elapsedRealtime() - playbackRequestedAt)
        if (generation != turnGeneration) return
        diagnostics.finishLiveCycle("completed")
        _uiState.update {
            it.copy(status = VoiceStatus.RECOGNIZING, activeSide = null, elapsedSeconds = 0)
        }
    }

    private fun stopLiveMode() {
        val state = _uiState.value
        if (!state.liveModeActive) return
        turnGeneration += 1
        liveTurnStopSignal = null
        liveJob?.cancel()
        liveJob = null
        timerJob?.cancel()
        activeSttProvider?.cancel()
        activeSttProvider = null
        systemTtsProvider.stop()
        livePreviousSpeaker = null
        diagnostics.event(
            "live_stopped_by_user",
            mapOf("phase" to state.status.name.lowercase()),
        )
        diagnostics.finishLiveSession("stopped_by_user")
        _uiState.update {
            it.copy(
                status = VoiceStatus.READY,
                liveModeActive = false,
                activeSide = null,
                partialTranscriptSide = null,
                elapsedSeconds = 0,
                error = null,
            )
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
        diagnostics.fail(throwable)
        _uiState.update {
            it.copy(
                status = VoiceStatus.ERROR,
                liveModeActive = false,
                activeSide = null,
                partialTranscriptSide = null,
                elapsedSeconds = 0,
                error = throwable.message ?: "Something went wrong.",
            )
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        liveJob?.cancel()
        languagePackJob?.cancel()
        activeSttProvider?.cancel()
        geminiLiveTranscribeProvider.cancel()
        pairLanguageClassifier.close()
        systemTtsProvider.release()
        super.onCleared()
    }

    private fun logTiming(stage: String, durationMs: Long) {
        Log.i(TIMING_TAG, "stage=$stage duration_ms=$durationMs")
        diagnostics.event(stage, mapOf("duration_ms" to durationMs.toString()))
    }

    private fun androidPackCheckFailure(
        providerName: String,
        throwable: Throwable,
    ): AndroidLanguagePackStatus = AndroidLanguagePackStatus(
        availability = AndroidLanguagePackAvailability.ERROR,
        providerName = providerName,
        detail = throwable.message ?: "Android language pack check failed.",
    )

    private companion object {
        const val TIMING_TAG = "SayItTiming"
    }
}

private data class LiveTranslationRoute(
    val sourceSide: LanguageSide,
    val translation: TranslationResult,
)

private fun LanguageSide.otherSide(): LanguageSide = when (this) {
    LanguageSide.A -> LanguageSide.B
    LanguageSide.B -> LanguageSide.A
}

private fun TranslatorUiState.languageFor(side: LanguageSide): AppLanguage = when (side) {
    LanguageSide.A -> languageA
    LanguageSide.B -> languageB
}

private fun endpointingDiagnosticFields(): Map<String, String> = mapOf(
    "endpointing" to "gemini_server_vad_with_local_rms_fallback",
    "gemini_server_vad_silence_ms" to GEMINI_SERVER_VAD_SILENCE_MS.toString(),
    "gemini_server_vad_prefix_padding_ms" to
        GEMINI_SERVER_VAD_PREFIX_PADDING_MS.toString(),
    "local_rms_fallback_ms" to GEMINI_LOCAL_RMS_FALLBACK_MS.toString(),
)

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
    SERVER_VAD(
        eventName = "server_vad_auto_stop",
        finalTimingStage = "stt_final_after_server_vad",
        playbackTimingStage = "server_vad_to_playback_start",
    ),
    LOCAL_RMS_FALLBACK(
        eventName = "local_rms_fallback_auto_stop",
        finalTimingStage = "stt_final_after_local_rms_fallback",
        playbackTimingStage = "local_rms_fallback_to_playback_start",
    ),
}

private enum class LiveStopTrigger(
    val eventName: String,
    val finalTimingStage: String,
    val playbackTimingStage: String,
) {
    SERVER_VAD(
        eventName = "server_vad_auto_stop",
        finalTimingStage = "stt_final_after_server_vad",
        playbackTimingStage = "server_vad_to_playback_start",
    ),
    LOCAL_RMS_FALLBACK(
        eventName = "local_rms_fallback_auto_stop",
        finalTimingStage = "stt_final_after_local_rms_fallback",
        playbackTimingStage = "local_rms_fallback_to_playback_start",
    ),
    MANUAL(
        eventName = "live_translate_tapped",
        finalTimingStage = "stt_final_after_live_translate_tap",
        playbackTimingStage = "live_translate_tap_to_playback_start",
    ),
}
