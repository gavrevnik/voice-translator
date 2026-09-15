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
import java.util.concurrent.TimeUnit

class GroqWhisperSttProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) : SttProvider {
    private val recorder = PcmWavRecorder()
    private var language = AppLanguage.ENGLISH

    override suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit) {
        if (BuildConfig.GROQ_API_KEY.isBlank()) {
            error("Groq API key is not configured in this build.")
        }
        this.language = language
        recorder.start()
    }

    override suspend fun stop(): String = withContext(Dispatchers.IO) {
        val wav = recorder.stop()
        if (wav.size > MAX_AUDIO_BYTES) {
            error("The recording is too long for the Groq free-tier upload limit.")
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "recording.wav",
                wav.toRequestBody(WAV_MEDIA_TYPE),
            )
            .addFormDataPart("model", GROQ_STT_MODEL)
            .addFormDataPart("language", language.whisperCode)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .build()
        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            .post(requestBody)
            .build()

        val startedAt = SystemClock.elapsedRealtime()
        client.newCall(request).execute().use { response ->
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
                throw IOException(message.ifBlank { "Groq API returned HTTP ${response.code}." })
            }

            payload?.optString("text")?.trim().orEmpty().ifBlank {
                error("Groq Whisper returned an empty transcript.")
            }
        }
    }

    override fun cancel() {
        recorder.cancel()
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
    private var output = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start() {
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

        output = ByteArrayOutputStream()
        failure = null
        audioRecord = recorder
        recording = true
        recorder.startRecording()
        worker = Thread({ captureLoop(recorder, minBufferSize) }, "SayItGroqRecorder").apply {
            start()
        }
    }

    fun stop(): ByteArray {
        check(recording) { "No recording is in progress." }
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        worker = null
        audioRecord?.release()
        audioRecord = null
        failure?.let { throw it }

        val pcm = output.toByteArray()
        if (pcm.isEmpty()) error("No audio was recorded.")
        return encodePcm16Wav(pcm, SAMPLE_RATE)
    }

    fun cancel() {
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(500)
        worker = null
        audioRecord?.release()
        audioRecord = null
        output.reset()
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
                output.write(bytes, 0, count * 2)
            }
        } catch (throwable: Throwable) {
            if (recording) failure = throwable
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

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
