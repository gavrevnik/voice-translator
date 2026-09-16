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
import com.whispercpp.whisper.WhisperCpuConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

class WhisperSttProvider(private val modelManager: WhisperModelManager) : SttProvider {
    private val audioRecorder = WhisperPcmAudioRecorder()
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inferenceMutex = Mutex()
    private var livePartialsEnabled = false
    private var livePartialJob: Job? = null
    private var partialResult: (String) -> Unit = {}
    private var sessionConfig = WhisperTranscriptionConfig(
        model = WHISPER_OFFLINE_MODEL,
        threads = 2,
        language = AppLanguage.ENGLISH.whisperCode,
    )
    private var whisperContext: WhisperContext? = null

    fun setLivePartialsEnabled(enabled: Boolean) {
        check(!audioRecorder.isRecording) { "Cannot change Whisper mode during recording." }
        livePartialsEnabled = enabled
    }

    override suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit) {
        val config = WhisperTranscriptionConfig(
            model = WHISPER_OFFLINE_MODEL,
            threads = WhisperCpuConfig.preferredThreadCount.coerceAtLeast(2),
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
        if (livePartialsEnabled) {
            livePartialJob = liveScope.launch { runLivePartialLoop(config) }
        }
    }

    override suspend fun stop(): String = withContext(Dispatchers.Default) {
        val stopTappedAt = SystemClock.elapsedRealtime()
        val samples = audioRecorder.stop()
        livePartialJob?.cancelAndJoin()
        livePartialJob = null
        partialResult = {}
        if (samples.isEmpty()) error("No audio was recorded.")
        val startedAt = SystemClock.elapsedRealtime()
        val cpuStartedAt = Process.getElapsedCpuTime()
        val transcript = transcribe(
            samples = samples,
            config = sessionConfig,
            initialPrompt = null,
            finalDecode = true,
        ).trim().ifBlank {
            error("Whisper could not recognize speech. Try speaking closer to the phone.")
        }
        Log.i(
            TIMING_TAG,
            "stage=whisper_final_decode duration_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "stop_to_final_ms=${SystemClock.elapsedRealtime() - stopTappedAt} " +
                "cpu_ms=${Process.getElapsedCpuTime() - cpuStartedAt} pss_kb=${Debug.getPss()} " +
                "audio_seconds=${samples.size / SAMPLE_RATE.toFloat()}",
        )
        deduplicateWhisperTranscript(transcript)
    }

    override fun cancel() {
        partialResult = {}
        livePartialJob?.cancel()
        livePartialJob = null
        audioRecorder.cancel()
    }

    suspend fun release() {
        partialResult = {}
        livePartialJob?.cancelAndJoin()
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
                    }
                }
                val cycleDurationMs = SystemClock.elapsedRealtime() - cycleStartedAt
                delay(
                    max(
                        MIN_PARTIAL_COOLDOWN_MS,
                        config.partialUpdateIntervalMs - cycleDurationMs,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.w(TIMING_TAG, "Whisper live partial decoding stopped; final decode remains available.", throwable)
        }
    }

    private suspend fun transcribe(
        samples: FloatArray,
        config: WhisperTranscriptionConfig,
        initialPrompt: String?,
        finalDecode: Boolean,
    ): String = inferenceMutex.withLock {
        ensureWhisperContextLocked().transcribeData(
            data = samples,
            language = config.language,
            numThreads = config.threads,
            initialPrompt = initialPrompt,
            finalDecode = finalDecode,
        )
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
}

internal data class WhisperTranscriptionConfig(
    val partialUpdateIntervalMs: Long = 1_000L,
    val slidingWindowSeconds: Int = 8,
    val model: String,
    val threads: Int,
    val language: String,
) {
    init {
        require(partialUpdateIntervalMs in 800L..1_200L)
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

private fun normalizeWhisperWord(word: String): String =
    word.trim().trim('.', ',', '!', '?', ':', ';', '«', '»', '"').lowercase()

private fun normalizedWhisperText(text: String): String = text
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private const val MIN_DUPLICATE_WORD_COUNT = 2
private const val SAMPLE_RATE = 16_000
private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 4 / 5
private const val MIN_PARTIAL_COOLDOWN_MS = 150L
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
