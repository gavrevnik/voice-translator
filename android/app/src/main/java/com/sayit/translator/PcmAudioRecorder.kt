package com.sayit.translator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

internal class PcmWavRecorder {
    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var failure: Throwable? = null
    private var speechWriter: SpeechOnlyPcmWriter? = null
    @Volatile private var autoStopCutPcmBytes: Int? = null

    @SuppressLint("MissingPermission")
    fun start(
        silenceDurationMs: Long,
        onSilenceDetected: () -> Unit,
        onPcmChunk: (ByteArray) -> Unit = {},
    ) {
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

        failure = null
        autoStopCutPcmBytes = null
        audioRecord = recorder
        recording = true
        recorder.startRecording()
        val pauseDetector = SpeechPauseDetector(
            sampleRate = SAMPLE_RATE,
            silenceDurationMs = silenceDurationMs,
        )
        val writer = SpeechOnlyPcmWriter(pauseDetector)
        speechWriter = writer
        worker = Thread(
            {
                captureLoop(
                    recorder = recorder,
                    minBufferSize = minBufferSize,
                    speechWriter = writer,
                    onSilenceDetected = onSilenceDetected,
                    onPcmChunk = onPcmChunk,
                )
            },
            "SayItPcmRecorder",
        ).apply { start() }
    }

    fun stop(allowNoLocalSpeech: Boolean = false): RecordedAudio {
        check(recording) { "No recording is in progress." }
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        worker = null
        audioRecord?.release()
        audioRecord = null
        failure?.let { throw it }

        val writer = speechWriter ?: error("No recording is in progress.")
        val speechPcm = writer.speechPcm()
        if (writer.capturedPcmBytes == 0) error("No audio was recorded.")
        if (speechPcm.isEmpty() && !allowNoLocalSpeech) error("No speech was detected.")
        val uploadPcm = if (speechPcm.isEmpty()) {
            speechPcm
        } else {
            val cutByteCount = (
                autoStopCutPcmBytes ?: autoStopPcmCutByteCount(
                    capturedBytesAtDetection = speechPcm.size,
                    trailingSilenceSamples = writer.trailingSilenceSamples,
                    sampleRate = SAMPLE_RATE,
                    postRollMs = AUDIO_AUTO_STOP_POST_ROLL_MS,
                )
                ).coerceIn(PCM16_BYTES_PER_SAMPLE, speechPcm.size)
            if (cutByteCount < speechPcm.size) speechPcm.copyOf(cutByteCount) else speechPcm
        }
        val recordedAudio = RecordedAudio(
            wav = encodePcm16Wav(uploadPcm, SAMPLE_RATE),
            capturedPcmBytes = writer.capturedPcmBytes,
            speechPcmBytes = speechPcm.size,
            uploadedPcmBytes = uploadPcm.size,
            sampleRate = SAMPLE_RATE,
        )
        speechWriter = null
        return recordedAudio
    }

    fun cancel() {
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(500)
        worker = null
        audioRecord?.release()
        audioRecord = null
        speechWriter = null
        autoStopCutPcmBytes = null
    }

    private fun captureLoop(
        recorder: AudioRecord,
        minBufferSize: Int,
        speechWriter: SpeechOnlyPcmWriter,
        onSilenceDetected: () -> Unit,
        onPcmChunk: (ByteArray) -> Unit,
    ) {
        val samples = ShortArray(minBufferSize.coerceAtLeast(2) / 2)
        try {
            while (recording) {
                val count = recorder.read(samples, 0, samples.size)
                if (count < 0) error("Microphone read failed with code $count.")
                if (count > 0) onPcmChunk(pcm16Bytes(samples, count))
                if (speechWriter.accept(samples, count)) {
                    autoStopCutPcmBytes = autoStopPcmCutByteCount(
                        capturedBytesAtDetection = speechWriter.speechPcmByteCount,
                        trailingSilenceSamples = speechWriter.trailingSilenceSamples,
                        sampleRate = SAMPLE_RATE,
                        postRollMs = AUDIO_AUTO_STOP_POST_ROLL_MS,
                    )
                    runCatching(onSilenceDetected).onFailure { throwable ->
                        Log.e(TIMING_TAG, "Silence auto-stop callback failed.", throwable)
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (recording) failure = throwable
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val TIMING_TAG = "SayItTiming"
    }
}

internal class SpeechOnlyPcmWriter(
    private val pauseDetector: SpeechPauseDetector,
) {
    private val output = ByteArrayOutputStream()
    private val pendingSpeech = ByteArrayOutputStream()

    var capturedPcmBytes: Int = 0
        private set

    val speechPcmByteCount: Int
        get() = output.size()

    val trailingSilenceSamples: Long
        get() = pauseDetector.trailingSilenceSamples

    fun accept(samples: ShortArray, count: Int): Boolean {
        require(count in 0..samples.size)
        if (count == 0) return false
        val bytes = pcm16Bytes(samples, count)
        capturedPcmBytes += bytes.size
        val speechAlreadyStarted = pauseDetector.hasSpeechStarted
        val shouldAutoStop = pauseDetector.accept(samples, count)
        when {
            speechAlreadyStarted -> output.write(bytes)
            pauseDetector.hasSpeechStarted -> {
                pendingSpeech.write(bytes)
                pendingSpeech.writeTo(output)
                pendingSpeech.reset()
            }
            pauseDetector.hasPotentialSpeech -> pendingSpeech.write(bytes)
            else -> pendingSpeech.reset()
        }
        return shouldAutoStop
    }

    fun speechPcm(): ByteArray = output.toByteArray()
}

internal fun pcm16Bytes(samples: ShortArray, count: Int): ByteArray {
    require(count in 0..samples.size)
    val bytes = ByteArray(count * PCM16_BYTES_PER_SAMPLE)
    var byteIndex = 0
    for (index in 0 until count) {
        val sample = samples[index].toInt()
        bytes[byteIndex++] = (sample and 0xff).toByte()
        bytes[byteIndex++] = ((sample shr 8) and 0xff).toByte()
    }
    return bytes
}

internal class SpeechPauseDetector(
    sampleRate: Int,
    silenceDurationMs: Long,
    private val speechStartRms: Double = 0.01,
    private val speechContinueRms: Double = 0.006,
    minimumSpeechMs: Long = 150,
) {
    private val silenceSamplesRequired = millisecondsToSamples(sampleRate, silenceDurationMs)
    private val speechSamplesRequired = millisecondsToSamples(sampleRate, minimumSpeechMs)
    private var consecutiveSpeechSamples = 0L
    private var consecutiveSilenceSamples = 0L
    private var speechStarted = false
    private var autoStopTriggered = false

    val hasSpeechStarted: Boolean
        get() = speechStarted

    val hasPotentialSpeech: Boolean
        get() = !speechStarted && consecutiveSpeechSamples > 0L

    val trailingSilenceSamples: Long
        get() = consecutiveSilenceSamples

    init {
        require(sampleRate > 0)
        require(silenceDurationMs > 0)
        require(speechStartRms > speechContinueRms)
        require(speechContinueRms > 0)
        require(minimumSpeechMs > 0)
    }

    fun accept(samples: ShortArray, count: Int): Boolean {
        if (autoStopTriggered || count <= 0) return false
        require(count <= samples.size)

        val rms = normalizedRms(samples, count)
        if (!speechStarted) {
            consecutiveSpeechSamples = if (rms >= speechStartRms) {
                consecutiveSpeechSamples + count
            } else {
                0L
            }
            if (consecutiveSpeechSamples >= speechSamplesRequired) {
                speechStarted = true
                consecutiveSilenceSamples = 0L
            }
            return false
        }

        consecutiveSilenceSamples = if (rms >= speechContinueRms) {
            0L
        } else {
            consecutiveSilenceSamples + count
        }
        if (consecutiveSilenceSamples < silenceSamplesRequired) return false

        autoStopTriggered = true
        return true
    }

    private fun millisecondsToSamples(sampleRate: Int, durationMs: Long): Long =
        (sampleRate.toLong() * durationMs / 1_000L).coerceAtLeast(1L)

    private fun normalizedRms(samples: ShortArray, count: Int): Double {
        var sumOfSquares = 0.0
        for (index in 0 until count) {
            val sample = samples[index].toDouble()
            sumOfSquares += sample * sample
        }
        return sqrt(sumOfSquares / count) / Short.MAX_VALUE.toDouble()
    }
}

internal data class RecordedAudio(
    val wav: ByteArray,
    val capturedPcmBytes: Int,
    val speechPcmBytes: Int,
    val uploadedPcmBytes: Int,
    val sampleRate: Int,
) {
    val capturedDurationMs: Long
        get() = pcmBytesToMilliseconds(capturedPcmBytes, sampleRate)

    val uploadedDurationMs: Long
        get() = pcmBytesToMilliseconds(uploadedPcmBytes, sampleRate)

    val trimmedLeadingDurationMs: Long
        get() = pcmBytesToMilliseconds(
            (capturedPcmBytes - speechPcmBytes).coerceAtLeast(0),
            sampleRate,
        )

    val trimmedTrailingDurationMs: Long
        get() = pcmBytesToMilliseconds(
            (speechPcmBytes - uploadedPcmBytes).coerceAtLeast(0),
            sampleRate,
        )
}

internal fun autoStopPcmCutByteCount(
    capturedBytesAtDetection: Int,
    trailingSilenceSamples: Long,
    sampleRate: Int,
    postRollMs: Int,
): Int {
    require(capturedBytesAtDetection >= 0)
    require(trailingSilenceSamples >= 0)
    require(sampleRate > 0)
    require(postRollMs >= 0)
    val postRollSamples = sampleRate.toLong() * postRollMs / 1_000L
    val removableSamples = (trailingSilenceSamples - postRollSamples).coerceAtLeast(0L)
    val removableBytes = removableSamples * PCM16_BYTES_PER_SAMPLE
    return (capturedBytesAtDetection.toLong() - removableBytes)
        .coerceIn(0L, capturedBytesAtDetection.toLong())
        .toInt()
        .let { byteCount -> byteCount - byteCount % PCM16_BYTES_PER_SAMPLE }
}

private fun pcmBytesToMilliseconds(byteCount: Int, sampleRate: Int): Long =
    byteCount.toLong() * 1_000L / (sampleRate * PCM16_BYTES_PER_SAMPLE)

internal fun encodePcm16Wav(pcm: ByteArray, sampleRate: Int): ByteArray =
    ByteBuffer.allocate(WAV_HEADER_BYTES + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(StandardCharsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }
        .array()

private const val WAV_HEADER_BYTES = 44
private const val PCM16_BYTES_PER_SAMPLE = 2
internal const val AUDIO_AUTO_STOP_POST_ROLL_MS = 300
