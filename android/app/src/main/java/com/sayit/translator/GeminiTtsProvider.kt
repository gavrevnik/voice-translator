package com.sayit.translator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiTtsProvider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
) : TtsProvider {
    private val activeLock = Any()
    private var activeCall: Call? = null
    private var activeTrack: AudioTrack? = null

    override suspend fun speak(
        text: String,
        language: AppLanguage,
        onStarted: (() -> Unit)?,
    ) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) error("Gemini API key is not configured in this build.")
        stop()
        val generated = generateSpeech(text, language)
        playPcm(generated, onStarted)
    }

    private fun generateSpeech(text: String, language: AppLanguage): GeneratedAudio {
        val prompt = """
            Read the text below aloud naturally in ${language.nativeName} (${language.bcp47}).
            Speak only the quoted text, without an introduction or commentary.

            <text>
            ${escapePromptValue(text)}
            </text>
        """.trimIndent()
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", "Kore"),
                            ),
                        ),
                    ),
            )
        val request = Request.Builder()
            .url("$GENERATE_CONTENT_URL/$GEMINI_TTS_MODEL:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        synchronized(activeLock) { activeCall = call }

        val startedAt = SystemClock.elapsedRealtime()
        try {
            call.execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                Log.i(
                    TIMING_TAG,
                    "stage=gemini_tts_http duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
                val body = runCatching { JSONObject(bodyText) }.getOrElse {
                    throw IOException("Gemini TTS returned an invalid response.", it)
                }
                if (!response.isSuccessful) {
                    val message = body.optJSONObject("error")?.optString("message").orEmpty()
                    throw IOException(
                        message.ifBlank { "Gemini TTS returned HTTP ${response.code}." },
                    )
                }
                return extractAudio(body)
            }
        } finally {
            synchronized(activeLock) {
                if (activeCall === call) activeCall = null
            }
        }
    }

    private fun extractAudio(body: JSONObject): GeneratedAudio {
        val parts = body.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IOException("Gemini TTS response contains no audio.")
        val pcm = ByteArrayOutputStream()
        var sampleRate = DEFAULT_SAMPLE_RATE
        for (index in 0 until parts.length()) {
            val inlineData = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
            val encoded = inlineData.optString("data")
            if (encoded.isBlank()) continue
            val mimeType = inlineData.optString("mimeType")
            RATE_PATTERN.find(mimeType)?.groupValues?.get(1)?.toIntOrNull()?.let {
                sampleRate = it
            }
            pcm.write(Base64.decode(encoded, Base64.DEFAULT))
        }
        val bytes = pcm.toByteArray()
        if (bytes.isEmpty()) throw IOException("Gemini TTS returned empty audio.")
        return GeneratedAudio(bytes, sampleRate)
    }

    private suspend fun playPcm(audio: GeneratedAudio, onStarted: (() -> Unit)?) {
        val minimumBufferSize = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBufferSize <= 0) error("Android audio output is unavailable.")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBufferSize, 16 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Android could not initialize Gemini TTS playback.")
        }
        synchronized(activeLock) { activeTrack = track }

        try {
            track.play()
            var offset = 0
            var playbackReported = false
            while (offset < audio.pcm.size && isActive(track)) {
                val written = track.write(
                    audio.pcm,
                    offset,
                    audio.pcm.size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written < 0) error("Android audio playback failed ($written).")
                offset += written
                if (!playbackReported && written > 0) {
                    playbackReported = true
                    onStarted?.invoke()
                }
            }
            val totalFrames = audio.pcm.size / PCM_BYTES_PER_FRAME
            while (isActive(track) && track.playbackHeadPosition < totalFrames) delay(20)
        } finally {
            if (detachTrack(track)) closeTrack(track)
        }
    }

    override fun stop() {
        val (call, track) = synchronized(activeLock) {
            val active = activeCall to activeTrack
            activeCall = null
            activeTrack = null
            active
        }
        call?.cancel()
        track?.let(::closeTrack)
    }

    override fun release() = stop()

    private fun isActive(track: AudioTrack): Boolean =
        synchronized(activeLock) { activeTrack === track }

    private fun detachTrack(track: AudioTrack): Boolean = synchronized(activeLock) {
        if (activeTrack !== track) return@synchronized false
        activeTrack = null
        true
    }

    private fun closeTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }

    private fun escapePromptValue(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private data class GeneratedAudio(val pcm: ByteArray, val sampleRate: Int)

    private companion object {
        const val GENERATE_CONTENT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val PCM_BYTES_PER_FRAME = 2
        const val TIMING_TAG = "SayItTiming"
        val RATE_PATTERN = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
