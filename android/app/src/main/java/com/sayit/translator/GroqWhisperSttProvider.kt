package com.sayit.translator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class GroqWhisperSttProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val diagnosticEvent: DiagnosticEvent = { _, _ -> },
) : SttProvider {
    private val recorder = PcmWavRecorder()
    private val activeHttpCall = AtomicReference<okhttp3.Call?>()
    private var requestedLanguageCode: String? = AppLanguage.ENGLISH.whisperCode

    override suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit) {
        start(
            language = language,
            silenceAutoStopSeconds = DEFAULT_SILENCE_AUTO_STOP_SECONDS,
            onSilenceAutoStop = {},
        )
    }

    suspend fun start(
        language: AppLanguage,
        silenceAutoStopSeconds: Float,
        onSilenceAutoStop: () -> Unit,
    ) {
        startRecording(
            languageCode = language.whisperCode,
            silenceAutoStopSeconds = silenceAutoStopSeconds,
            onSilenceAutoStop = onSilenceAutoStop,
        )
    }

    suspend fun startLive(
        silenceAutoStopSeconds: Float,
        onSilenceAutoStop: () -> Unit,
    ) {
        startRecording(
            languageCode = null,
            silenceAutoStopSeconds = silenceAutoStopSeconds,
            onSilenceAutoStop = onSilenceAutoStop,
        )
    }

    private fun startRecording(
        languageCode: String?,
        silenceAutoStopSeconds: Float,
        onSilenceAutoStop: () -> Unit,
    ) {
        if (BuildConfig.GROQ_API_KEY.isBlank()) {
            error("Groq API key is not configured in this build.")
        }
        requestedLanguageCode = languageCode
        recorder.start(
            silenceDurationMs = silenceAutoStopDurationMs(silenceAutoStopSeconds),
            onSilenceDetected = onSilenceAutoStop,
        )
    }

    override suspend fun stop(): String = finishTranscription(requireDetectedLanguage = false).text

    suspend fun stopWithLanguageDetection(): GroqTranscription =
        finishTranscription(requireDetectedLanguage = true)

    private suspend fun finishTranscription(
        requireDetectedLanguage: Boolean,
    ): GroqTranscription = withContext(Dispatchers.IO) {
        val recording = recorder.stop()
        val wav = recording.wav
        if (wav.size > MAX_AUDIO_BYTES) {
            error("The recording is too long for the Groq free-tier upload limit.")
        }
        diagnosticEvent(
            "groq_audio_prepared",
            mapOf(
                "captured_audio_ms" to recording.capturedDurationMs.toString(),
                "uploaded_audio_ms" to recording.uploadedDurationMs.toString(),
                "trimmed_leading_silence_ms" to recording.trimmedLeadingDurationMs.toString(),
                "trimmed_trailing_silence_ms" to recording.trimmedTrailingDurationMs.toString(),
                "silence_trim_applied" to
                    (recording.trimmedTrailingDurationMs > 0).toString(),
                "post_roll_ms" to GROQ_AUTO_STOP_POST_ROLL_MS.toString(),
            ),
        )

        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "recording.wav",
                wav.toRequestBody(WAV_MEDIA_TYPE),
            )
            .addFormDataPart("model", GROQ_STT_MODEL)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
        requestedLanguageCode?.let { languageCode ->
            requestBodyBuilder.addFormDataPart("language", languageCode)
        }
        val requestBody = requestBodyBuilder.build()
        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            .post(requestBody)
            .build()

        val startedAt = SystemClock.elapsedRealtime()
        val call = client.newCall(request)
        activeHttpCall.set(call)
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.i(
                    TIMING_TAG,
                    "stage=groq_whisper_large_v3 duration_ms=" +
                        "${SystemClock.elapsedRealtime() - startedAt} audio_bytes=${wav.size}",
                )
                val payload = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful) {
                    val message = payload
                        ?.optJSONObject("error")
                        ?.optString("message")
                        .orEmpty()
                    throw IOException(
                        message.ifBlank { "Groq API returned HTTP ${response.code}." },
                    )
                }

                if (payload != null) recordVerboseDiagnostics(payload)
                val transcript = payload?.optString("text")?.trim().orEmpty()
                if (transcript.isBlank()) {
                    error("Groq Whisper returned an empty transcript.")
                }
                val detectedLanguage = payload?.optString("language")?.trim().orEmpty()
                if (requireDetectedLanguage && detectedLanguage.isBlank()) {
                    error("Groq Whisper did not return a detected language.")
                }
                GroqTranscription(
                    text = transcript,
                    detectedLanguage = detectedLanguage,
                )
            }
        } finally {
            activeHttpCall.compareAndSet(call, null)
        }
    }

    override fun cancel() {
        recorder.cancel()
        activeHttpCall.getAndSet(null)?.cancel()
    }

    private fun recordVerboseDiagnostics(payload: JSONObject) {
        val segments = payload.optJSONArray("segments")
        val segmentObjects = if (segments == null) {
            emptyList()
        } else {
            (0 until segments.length()).mapNotNull(segments::optJSONObject)
        }
        val fields = linkedMapOf(
            "response_format" to "verbose_json",
            "requested_language" to (requestedLanguageCode ?: "auto"),
            "detected_language" to payload.optString("language", "unknown"),
            "segment_count" to segmentObjects.size.toString(),
        )
        payload.optFiniteDouble("duration")?.let { durationSeconds ->
            fields["response_audio_ms"] = (durationSeconds * 1_000).toLong().toString()
        }
        segmentObjects.mapNotNull { it.optFiniteDouble("avg_logprob") }.minOrNull()?.let {
            fields["minimum_avg_logprob"] = it.asDiagnosticDecimal()
        }
        segmentObjects.mapNotNull { it.optFiniteDouble("compression_ratio") }.maxOrNull()?.let {
            fields["maximum_compression_ratio"] = it.asDiagnosticDecimal()
        }
        segmentObjects.mapNotNull { it.optFiniteDouble("no_speech_prob") }.maxOrNull()?.let {
            fields["maximum_no_speech_prob"] = it.asDiagnosticDecimal()
        }
        segmentObjects.lastOrNull()?.let { lastSegment ->
            lastSegment.optFiniteDouble("start")?.let {
                fields["last_segment_start_ms"] = (it * 1_000).toLong().toString()
            }
            lastSegment.optFiniteDouble("end")?.let {
                fields["last_segment_end_ms"] = (it * 1_000).toLong().toString()
            }
            lastSegment.optFiniteDouble("avg_logprob")?.let {
                fields["last_segment_avg_logprob"] = it.asDiagnosticDecimal()
            }
            lastSegment.optFiniteDouble("compression_ratio")?.let {
                fields["last_segment_compression_ratio"] = it.asDiagnosticDecimal()
            }
            lastSegment.optFiniteDouble("no_speech_prob")?.let {
                fields["last_segment_no_speech_prob"] = it.asDiagnosticDecimal()
            }
        }
        diagnosticEvent("groq_whisper_verbose", fields)
    }

    private companion object {
        const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
        const val TIMING_TAG = "SayItTiming"
        val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}

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
            "SayItGroqRecorder",
        ).apply { start() }
    }

    fun stop(): GroqRecordedAudio {
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
        if (speechPcm.isEmpty()) error("No speech was detected.")
        val cutByteCount = (
            autoStopCutPcmBytes ?: autoStopPcmCutByteCount(
                capturedBytesAtDetection = speechPcm.size,
                trailingSilenceSamples = writer.trailingSilenceSamples,
                sampleRate = SAMPLE_RATE,
                postRollMs = GROQ_AUTO_STOP_POST_ROLL_MS,
            )
            ).coerceIn(PCM16_BYTES_PER_SAMPLE, speechPcm.size)
        val uploadPcm = if (cutByteCount < speechPcm.size) {
            speechPcm.copyOf(cutByteCount)
        } else {
            speechPcm
        }
        val recordedAudio = GroqRecordedAudio(
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
                        postRollMs = GROQ_AUTO_STOP_POST_ROLL_MS,
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
    private val silenceSamplesRequired = millisecondsToSamples(
        sampleRate = sampleRate,
        durationMs = silenceDurationMs,
    )
    private val speechSamplesRequired = millisecondsToSamples(
        sampleRate = sampleRate,
        durationMs = minimumSpeechMs,
    )
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

internal data class GroqRecordedAudio(
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

data class GroqTranscription(
    val text: String,
    val detectedLanguage: String,
)

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

private fun JSONObject.optFiniteDouble(name: String): Double? =
    optDouble(name, Double.NaN).takeIf(Double::isFinite)

private fun Double.asDiagnosticDecimal(): String =
    String.format(java.util.Locale.US, "%.4f", this)

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
internal const val GROQ_AUTO_STOP_POST_ROLL_MS = 300
