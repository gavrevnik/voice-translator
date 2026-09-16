package com.sayit.translator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class WhisperSttProvider(
    private val modelManager: WhisperModelManager,
    private val diagnosticEvent: DiagnosticEvent = { _, _ -> },
) : SttProvider {
    private val audioRecorder = WhisperPcmAudioRecorder()
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inferenceMutex = Mutex()
    private var livePartialsEnabled = false
    private var livePartialJob: Job? = null
    private var partialResult: (String) -> Unit = {}
    private var sessionConfig = WhisperTranscriptionConfig(
        model = WHISPER_OFFLINE_MODEL,
        threads = WHISPER_INFERENCE_THREADS,
        language = AppLanguage.ENGLISH.whisperCode,
    )
    @Volatile
    private var whisperContext: WhisperContext? = null

    fun setLivePartialsEnabled(enabled: Boolean) {
        check(!audioRecorder.isRecording) { "Cannot change Whisper mode during recording." }
        livePartialsEnabled = enabled
    }

    suspend fun prepare(): Unit = withContext(Dispatchers.Default) {
        val startedAt = SystemClock.elapsedRealtime()
        val wasLoaded = whisperContext != null
        ensureWhisperContext()
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(
            TIMING_TAG,
            "stage=whisper_model_prepare duration_ms=$durationMs warm=$wasLoaded",
        )
        diagnosticEvent(
            "whisper_model_prepare",
            mapOf(
                "duration_ms" to durationMs.toString(),
                "context_already_loaded" to wasLoaded.toString(),
            ),
        )
    }

    override suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit) {
        val config = WhisperTranscriptionConfig(
            model = WHISPER_OFFLINE_MODEL,
            threads = WHISPER_INFERENCE_THREADS,
            language = language.whisperCode,
        )
        check(modelManager.manifest.id.endsWith(config.model)) {
            "Installed Whisper model does not match ${config.model}."
        }
        sessionConfig = config
        partialResult = onPartialResult
        audioRecorder.start(
            rollingWindowSamples = if (livePartialsEnabled) config.slidingWindowSamples else 0,
        )
        Log.i(
            TIMING_TAG,
            "event=whisper_session_started live=$livePartialsEnabled " +
                "model=${config.model} language=${config.language} threads=${config.threads} " +
                "partial_interval_ms=${config.partialUpdateIntervalMs} " +
                "sliding_window_seconds=${config.slidingWindowSeconds}",
        )
        diagnosticEvent(
            "whisper_session_started",
            mapOf(
                "live" to livePartialsEnabled.toString(),
                "model" to config.model,
                "language" to config.language,
                "threads" to config.threads.toString(),
                "partial_interval_ms" to config.partialUpdateIntervalMs.toString(),
                "rolling_window_seconds" to config.slidingWindowSeconds.toString(),
                "final_beam_size" to WHISPER_FINAL_BEAM_SIZE.toString(),
                "final_timestamps" to "false",
                "final_temperature_fallback" to "false",
            ),
        )
        if (livePartialsEnabled) {
            livePartialJob = liveScope.launch { runLivePartialLoop(config) }
        }
    }

    override suspend fun stop(): String = withContext(Dispatchers.Default) {
        val stopTappedAt = SystemClock.elapsedRealtime()
        delay(WHISPER_FINAL_CAPTURE_GRACE_MS)
        val samples = audioRecorder.stop()
        val partialJob = livePartialJob
        partialJob?.cancel()
        whisperContext?.requestAbort()
        partialJob?.join()
        livePartialJob = null
        partialResult = {}
        if (samples.isEmpty()) error("No audio was recorded.")
        val decodeSamples = trimOuterSilence(samples, sessionConfig)
        val startedAt = SystemClock.elapsedRealtime()
        val cpuStartedAt = Process.getElapsedCpuTime()
        val transcript = transcribe(
            samples = decodeSamples,
            config = sessionConfig,
            initialPrompt = null,
            finalDecode = true,
        ).trim().ifBlank {
            error("Whisper could not recognize speech. Try speaking closer to the phone.")
        }
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        val stopToFinalMs = SystemClock.elapsedRealtime() - stopTappedAt
        val cpuMs = Process.getElapsedCpuTime() - cpuStartedAt
        Log.i(
            TIMING_TAG,
            "stage=whisper_final_decode duration_ms=$durationMs " +
                "stop_to_final_ms=$stopToFinalMs " +
                "cpu_ms=$cpuMs pss_kb=${Debug.getPss()} " +
                "input_audio_seconds=${samples.size / SAMPLE_RATE.toFloat()} " +
                "decode_audio_seconds=${decodeSamples.size / SAMPLE_RATE.toFloat()}",
        )
        diagnosticEvent(
            "whisper_final_decode",
            mapOf(
                "duration_ms" to durationMs.toString(),
                "stop_to_final_ms" to stopToFinalMs.toString(),
                "cpu_ms" to cpuMs.toString(),
                "pss_kb" to Debug.getPss().toString(),
                "input_audio_ms" to samplesToMilliseconds(samples.size).toString(),
                "decode_audio_ms" to samplesToMilliseconds(decodeSamples.size).toString(),
            ),
        )
        deduplicateWhisperTranscript(transcript)
    }

    override fun cancel() {
        partialResult = {}
        livePartialJob?.cancel()
        whisperContext?.requestAbort()
        livePartialJob = null
        audioRecorder.cancel()
    }

    suspend fun release() {
        partialResult = {}
        val partialJob = livePartialJob
        partialJob?.cancel()
        whisperContext?.requestAbort()
        partialJob?.join()
        livePartialJob = null
        inferenceMutex.withLock {
            whisperContext?.release()
            whisperContext = null
        }
    }

    private suspend fun runLivePartialLoop(config: WhisperTranscriptionConfig) {
        val sessionStartedAt = SystemClock.elapsedRealtime()
        var previousResultAt = 0L
        var firstResultLatencyMs = 0L
        var intervalTotalMs = 0L
        var resultCount = 0
        var decodedSampleCount = 0L
        val assembler = WhisperPartialTranscriptAssembler()
        try {
            ensureWhisperContext()
            val initialDelay = config.partialUpdateIntervalMs -
                (SystemClock.elapsedRealtime() - sessionStartedAt)
            if (initialDelay > 0) delay(initialDelay)
            while (currentCoroutineContext().isActive) {
                val cycleStartedAt = SystemClock.elapsedRealtime()
                val snapshot = audioRecorder.rollingSnapshot()
                if (
                    snapshot.totalSampleCount > decodedSampleCount &&
                    snapshot.samples.size >= MIN_PARTIAL_SAMPLES
                ) {
                    val cpuStartedAt = Process.getElapsedCpuTime()
                    val windowTranscript = transcribe(
                        samples = snapshot.samples,
                        config = config,
                        initialPrompt = assembler.contextPrompt(),
                        finalDecode = false,
                    ).trim()
                    decodedSampleCount = snapshot.totalSampleCount
                    val completedAt = SystemClock.elapsedRealtime()
                    val mergedTranscript = assembler.update(windowTranscript)
                    if (mergedTranscript.isNotBlank() && mergedTranscript != assembler.lastEmitted) {
                        if (previousResultAt != 0L) {
                            intervalTotalMs += completedAt - previousResultAt
                        }
                        previousResultAt = completedAt
                        resultCount += 1
                        if (resultCount == 1) {
                            firstResultLatencyMs = completedAt - sessionStartedAt
                        }
                        assembler.lastEmitted = mergedTranscript
                        partialResult(mergedTranscript)
                        Log.i(
                            TIMING_TAG,
                            "stage=whisper_live_partial result=$resultCount " +
                                "decode_ms=${completedAt - cycleStartedAt} " +
                                "first_latency_ms=$firstResultLatencyMs " +
                                "average_update_interval_ms=" +
                                averageInterval(intervalTotalMs, resultCount) + " " +
                                "cpu_ms=${Process.getElapsedCpuTime() - cpuStartedAt} " +
                                "pss_kb=${Debug.getPss()} window_samples=${snapshot.samples.size} " +
                                "overrun_ms=${max(0L, completedAt - cycleStartedAt - config.partialUpdateIntervalMs)} " +
                                "pending_inferences=0",
                        )
                        diagnosticEvent(
                            "whisper_live_partial",
                            mapOf(
                                "result" to resultCount.toString(),
                                "decode_ms" to (completedAt - cycleStartedAt).toString(),
                                "first_latency_ms" to firstResultLatencyMs.toString(),
                                "average_update_interval_ms" to
                                    averageInterval(intervalTotalMs, resultCount).toString(),
                                "cpu_ms" to
                                    (Process.getElapsedCpuTime() - cpuStartedAt).toString(),
                                "window_audio_ms" to
                                    samplesToMilliseconds(snapshot.samples.size).toString(),
                                "overrun_ms" to max(
                                    0L,
                                    completedAt - cycleStartedAt - config.partialUpdateIntervalMs,
                                ).toString(),
                            ),
                        )
                    }
                }
                val cycleDurationMs = SystemClock.elapsedRealtime() - cycleStartedAt
                delay(livePartialDelayMs(cycleDurationMs, config.partialUpdateIntervalMs))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.w(TIMING_TAG, "Whisper live partial decoding stopped; final decode remains available.", throwable)
            diagnosticEvent(
                "whisper_live_partial_failed",
                mapOf(
                    "error_type" to throwable.javaClass.simpleName,
                    "error_message" to (throwable.message ?: "Unknown error"),
                ),
            )
        }
    }

    private suspend fun transcribe(
        samples: FloatArray,
        config: WhisperTranscriptionConfig,
        initialPrompt: String?,
        finalDecode: Boolean,
    ): String = inferenceMutex.withLock {
        val context = ensureWhisperContextLocked()
        val text = context.transcribeData(
            data = samples,
            language = config.language,
            numThreads = config.threads,
            initialPrompt = initialPrompt,
            finalDecode = finalDecode,
        )
        val timings = context.detailedTimings()
        val timingFields = mapOf(
            "mode" to if (finalDecode) "final" else "partial",
            "mel_ms" to microsecondsToMilliseconds(timings.melUs),
            "sample_ms" to microsecondsToMilliseconds(timings.sampleUs),
            "sample_runs" to timings.sampleRuns.toString(),
            "encode_ms" to microsecondsToMilliseconds(timings.encodeUs),
            "encode_runs" to timings.encodeRuns.toString(),
            "decode_ms" to microsecondsToMilliseconds(timings.decodeUs),
            "decode_runs" to timings.decodeRuns.toString(),
            "batch_decode_ms" to microsecondsToMilliseconds(timings.batchdUs),
            "batch_decode_runs" to timings.batchdRuns.toString(),
            "prompt_ms" to microsecondsToMilliseconds(timings.promptUs),
            "prompt_runs" to timings.promptRuns.toString(),
            "fallback_prompt_runs" to timings.fallbackPromptRuns.toString(),
            "fallback_hallucination_runs" to
                timings.fallbackHallucinationRuns.toString(),
        )
        diagnosticEvent("whisper_native_timings", timingFields)
        Log.i(
            TIMING_TAG,
            "stage=whisper_native_timings " +
                timingFields.entries.joinToString(" ") { (key, value) -> "$key=$value" },
        )
        text
    }

    private suspend fun trimOuterSilence(
        samples: FloatArray,
        config: WhisperTranscriptionConfig,
    ): FloatArray {
        val startedAt = SystemClock.elapsedRealtime()
        val speechBounds = runCatching {
            val vadModel = modelManager.installedVadModel()
            inferenceMutex.withLock {
                ensureWhisperContextLocked().detectSpeechBounds(
                    data = samples,
                    vadModelPath = vadModel.absolutePath,
                    numThreads = config.threads,
                    threshold = WHISPER_VAD_THRESHOLD,
                    minSpeechDurationMs = WHISPER_VAD_MIN_SPEECH_MS,
                    minSilenceDurationMs = WHISPER_VAD_MIN_SILENCE_MS,
                )
            }
        }.onFailure {
            Log.w(TIMING_TAG, "Whisper VAD failed; using the complete recording.", it)
            diagnosticEvent(
                "whisper_vad_failed",
                mapOf(
                    "error_type" to it.javaClass.simpleName,
                    "error_message" to (it.message ?: "Unknown error"),
                ),
            )
        }.getOrNull()
        val trimmed = trimToOuterSpeech(samples, speechBounds)
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(
            TIMING_TAG,
            "stage=whisper_vad duration_ms=$durationMs " +
                "applied=${trimmed !== samples} " +
                "input_audio_seconds=${samples.size / SAMPLE_RATE.toFloat()} " +
                "decode_audio_seconds=${trimmed.size / SAMPLE_RATE.toFloat()}",
        )
        diagnosticEvent(
            "whisper_vad",
            mapOf(
                "duration_ms" to durationMs.toString(),
                "applied" to (trimmed !== samples).toString(),
                "input_audio_ms" to samplesToMilliseconds(samples.size).toString(),
                "decode_audio_ms" to samplesToMilliseconds(trimmed.size).toString(),
            ),
        )
        return trimmed
    }

    private suspend fun ensureWhisperContext() {
        inferenceMutex.withLock { ensureWhisperContextLocked() }
    }

    private fun ensureWhisperContextLocked(): WhisperContext =
        whisperContext ?: WhisperContext.createContextFromFile(
            modelManager.installedModel().absolutePath,
        ).also { whisperContext = it }

    private fun averageInterval(intervalTotalMs: Long, resultCount: Int): Long =
        if (resultCount <= 1) 0L else intervalTotalMs / (resultCount - 1)

    private fun samplesToMilliseconds(sampleCount: Int): Long =
        sampleCount * 1_000L / SAMPLE_RATE

    private fun microsecondsToMilliseconds(microseconds: Long): String =
        String.format(java.util.Locale.US, "%.2f", microseconds / 1_000.0)
}

internal data class WhisperTranscriptionConfig(
    val partialUpdateIntervalMs: Long = 1_500L,
    val slidingWindowSeconds: Int = 6,
    val model: String,
    val threads: Int,
    val language: String,
) {
    init {
        require(partialUpdateIntervalMs in 1_000L..3_000L)
        require(slidingWindowSeconds in 5..10)
        require(model.isNotBlank())
        require(threads > 0)
        require(language.isNotBlank() && language != "auto")
    }

    val slidingWindowSamples: Int = slidingWindowSeconds * SAMPLE_RATE
}

internal class WhisperPartialTranscriptAssembler {
    private var transcript = ""
    var lastEmitted: String = ""

    fun update(windowTranscript: String): String {
        if (windowTranscript.isBlank()) return transcript
        val nextWindow = deduplicateWhisperTranscript(windowTranscript)
        val currentWords = transcript.split(Regex("\\s+")).filter(String::isNotBlank)
        val nextWords = nextWindow.split(Regex("\\s+")).filter(String::isNotBlank)
        transcript = when {
            normalizedWhisperText(transcript) == normalizedWhisperText(nextWindow) -> transcript
            currentWords.size == 1 &&
                nextWords.size > 1 &&
                normalizedWhisperText(currentWords.single()) ==
                normalizedWhisperText(nextWords.first()) -> nextWindow
            else -> mergeRecognitionTranscripts(transcript, nextWindow)
        }
        return transcript
    }

    fun contextPrompt(): String? = transcript
        .takeLast(MAX_INITIAL_PROMPT_CHARS)
        .takeIf(String::isNotBlank)
}

internal fun deduplicateWhisperTranscript(transcript: String): String {
    val words = transcript.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.size < MIN_DUPLICATE_WORD_COUNT * 2 || words.size % 2 != 0) return transcript.trim()
    val midpoint = words.size / 2
    val firstHalf = words.take(midpoint)
    val secondHalf = words.drop(midpoint)
    val isDuplicate = firstHalf.indices.all { index ->
        normalizeWhisperWord(firstHalf[index]) == normalizeWhisperWord(secondHalf[index])
    }
    return if (isDuplicate) firstHalf.joinToString(" ") else transcript.trim()
}

internal fun livePartialDelayMs(cycleDurationMs: Long, targetIntervalMs: Long): Long =
    if (cycleDurationMs < targetIntervalMs) {
        max(MIN_PARTIAL_COOLDOWN_MS, targetIntervalMs - cycleDurationMs)
    } else {
        // Turbo can take longer than the target cadence on a phone. Give the
        // CPU a bounded cooldown instead of immediately starting another pass.
        (cycleDurationMs / 4).coerceIn(
            MIN_OVERRUN_COOLDOWN_MS,
            targetIntervalMs,
        )
    }

internal fun trimToOuterSpeech(
    samples: FloatArray,
    speechBounds: LongArray?,
    sampleRate: Int = SAMPLE_RATE,
    preRollMs: Int = WHISPER_VAD_PRE_ROLL_MS,
    postRollMs: Int = WHISPER_VAD_POST_ROLL_MS,
    minSavedMs: Int = WHISPER_VAD_MIN_SAVED_MS,
): FloatArray {
    if (speechBounds == null || speechBounds.size != 2 || samples.isEmpty()) return samples
    val speechStart = speechBounds[0].coerceIn(0L, samples.size.toLong()).toInt()
    val speechEnd = speechBounds[1].coerceIn(0L, samples.size.toLong()).toInt()
    if (speechEnd <= speechStart) return samples
    val trimStart = (speechStart - preRollMs * sampleRate / 1_000).coerceAtLeast(0)
    val trimEnd = (speechEnd + postRollMs * sampleRate / 1_000).coerceAtMost(samples.size)
    val savedSamples = samples.size - (trimEnd - trimStart)
    if (savedSamples < minSavedMs * sampleRate / 1_000) return samples
    return samples.copyOfRange(trimStart, trimEnd)
}

private fun normalizeWhisperWord(word: String): String =
    word.trim().trim('.', ',', '!', '?', ':', ';', '«', '»', '"').lowercase()

private fun normalizedWhisperText(text: String): String = text
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private const val MIN_DUPLICATE_WORD_COUNT = 2
internal const val SAMPLE_RATE = 16_000
internal const val WHISPER_INFERENCE_THREADS = 6
internal const val WHISPER_FINAL_BEAM_SIZE = 3
internal const val WHISPER_FINAL_CAPTURE_GRACE_MS = 200L
internal const val WHISPER_VAD_THRESHOLD = 0.5f
internal const val WHISPER_VAD_MIN_SPEECH_MS = 150
internal const val WHISPER_VAD_MIN_SILENCE_MS = 300
internal const val WHISPER_VAD_PRE_ROLL_MS = 250
internal const val WHISPER_VAD_POST_ROLL_MS = 350
internal const val WHISPER_VAD_MIN_SAVED_MS = 500
private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 4 / 5
private const val MIN_PARTIAL_COOLDOWN_MS = 150L
private const val MIN_OVERRUN_COOLDOWN_MS = 500L
private const val MAX_INITIAL_PROMPT_CHARS = 400
private const val TIMING_TAG = "SayItTiming"

private class WhisperPcmAudioRecorder {
    @Volatile private var recording = false
    @Volatile private var failure: Throwable? = null
    private val bufferLock = Any()
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var output = ByteArrayOutputStream()
    private var rollingSamples = ShortArray(0)
    private var rollingWriteIndex = 0
    private var rollingSampleCount = 0
    private var totalSampleCount = 0L

    val isRecording: Boolean
        get() = recording

    @SuppressLint("MissingPermission")
    fun start(rollingWindowSamples: Int) {
        check(!recording) { "Recording is already active." }
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "16 kHz microphone recording is unavailable." }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Could not initialize the microphone."
        }

        synchronized(bufferLock) {
            output = ByteArrayOutputStream()
            rollingSamples = ShortArray(rollingWindowSamples)
            rollingWriteIndex = 0
            rollingSampleCount = 0
            totalSampleCount = 0L
        }
        failure = null
        audioRecord = recorder
        recording = true
        recorder.startRecording()
        worker = Thread({ captureLoop(recorder, minBufferSize) }, "SayItWhisperRecorder").apply {
            start()
        }
    }

    fun stop(): FloatArray {
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        worker = null
        audioRecord?.release()
        audioRecord = null
        failure?.let { throw it }

        val bytes = synchronized(bufferLock) { output.toByteArray() }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get() / 32768f }
    }

    fun rollingSnapshot(): WhisperAudioSnapshot {
        val pcm = synchronized(bufferLock) {
            if (rollingSampleCount == 0 || rollingSamples.isEmpty()) {
                return@synchronized WhisperPcm16Snapshot(ShortArray(0), totalSampleCount)
            }
            val snapshot = ShortArray(rollingSampleCount)
            val start = (rollingWriteIndex - rollingSampleCount + rollingSamples.size) %
                rollingSamples.size
            val firstPartSize = minOf(snapshot.size, rollingSamples.size - start)
            rollingSamples.copyInto(
                destination = snapshot,
                destinationOffset = 0,
                startIndex = start,
                endIndex = start + firstPartSize,
            )
            if (firstPartSize < snapshot.size) {
                rollingSamples.copyInto(
                    destination = snapshot,
                    destinationOffset = firstPartSize,
                    startIndex = 0,
                    endIndex = snapshot.size - firstPartSize,
                )
            }
            WhisperPcm16Snapshot(snapshot, totalSampleCount)
        }
        return WhisperAudioSnapshot(
            samples = FloatArray(pcm.samples.size) { pcm.samples[it] / 32768f },
            totalSampleCount = pcm.totalSampleCount,
        )
    }

    fun cancel() {
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(500)
        worker = null
        audioRecord?.release()
        audioRecord = null
        synchronized(bufferLock) {
            output.reset()
            rollingSamples = ShortArray(0)
            rollingWriteIndex = 0
            rollingSampleCount = 0
            totalSampleCount = 0L
        }
    }

    private fun captureLoop(recorder: AudioRecord, minBufferSize: Int) {
        val samples = ShortArray(minBufferSize.coerceAtLeast(2) / 2)
        val bytes = ByteArray(samples.size * 2)
        try {
            while (recording) {
                val count = recorder.read(samples, 0, samples.size)
                if (count < 0) error("Microphone read failed with code $count.")
                var byteIndex = 0
                for (index in 0 until count) {
                    val sample = samples[index].toInt()
                    bytes[byteIndex++] = (sample and 0xff).toByte()
                    bytes[byteIndex++] = ((sample shr 8) and 0xff).toByte()
                }
                synchronized(bufferLock) {
                    output.write(bytes, 0, count * 2)
                    totalSampleCount += count
                    if (rollingSamples.isNotEmpty()) {
                        for (index in 0 until count) {
                            rollingSamples[rollingWriteIndex] = samples[index]
                            rollingWriteIndex = (rollingWriteIndex + 1) % rollingSamples.size
                            rollingSampleCount = minOf(
                                rollingSampleCount + 1,
                                rollingSamples.size,
                            )
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (recording) failure = throwable
        }
    }

}

private data class WhisperAudioSnapshot(
    val samples: FloatArray,
    val totalSampleCount: Long,
)

private data class WhisperPcm16Snapshot(
    val samples: ShortArray,
    val totalSampleCount: Long,
)
