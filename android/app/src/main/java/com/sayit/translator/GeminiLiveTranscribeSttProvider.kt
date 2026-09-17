package com.sayit.translator

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GeminiLiveTranscribeSttProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val diagnosticEvent: DiagnosticEvent = { _, _ -> },
) : SttProvider {
    private val recorder = PcmWavRecorder()
    private val activeSession = AtomicReference<LiveSession?>()

    override suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit) {
        start(
            language = language,
            silenceAutoStopSeconds = DEFAULT_SILENCE_AUTO_STOP_SECONDS,
            onSilenceAutoStop = {},
            onPartialResult = onPartialResult,
        )
    }

    suspend fun start(
        language: AppLanguage,
        silenceAutoStopSeconds: Float,
        onSilenceAutoStop: () -> Unit,
        onPartialResult: (String) -> Unit,
    ) {
        startSession(
            languages = listOf(language),
            silenceAutoStopSeconds = silenceAutoStopSeconds,
            onSilenceAutoStop = onSilenceAutoStop,
            onPartialResult = onPartialResult,
        )
    }

    suspend fun startLive(
        languageA: AppLanguage,
        languageB: AppLanguage,
        silenceAutoStopSeconds: Float,
        onSilenceAutoStop: () -> Unit,
        onPartialResult: (String) -> Unit,
    ) {
        startSession(
            languages = listOf(languageA, languageB).distinct(),
            silenceAutoStopSeconds = silenceAutoStopSeconds,
            onSilenceAutoStop = onSilenceAutoStop,
            onPartialResult = onPartialResult,
        )
    }

    private suspend fun startSession(
        languages: List<AppLanguage>,
        silenceAutoStopSeconds: Float,
        onSilenceAutoStop: () -> Unit,
        onPartialResult: (String) -> Unit,
    ) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank()) error("Gemini API key is not configured in this build.")
        require(languages.isNotEmpty()) { "At least one transcription language is required." }
        check(activeSession.get() == null) { "Gemini Live transcription is already active." }

        val languageCodes = languages.map(AppLanguage::bcp47)
        val session = LiveSession(
            languageCodes = languageCodes,
            onPartialResult = onPartialResult,
        )
        check(activeSession.compareAndSet(null, session)) {
            "Gemini Live transcription is already active."
        }
        val requestUrl = LIVE_API_URL.toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(requestUrl).build()
        val connectedAt = SystemClock.elapsedRealtime()

        try {
            val socket = client.newWebSocket(request, listenerFor(session))
            session.attach(socket)
            recorder.start(
                silenceDurationMs = silenceAutoStopDurationMs(silenceAutoStopSeconds),
                onSilenceDetected = onSilenceAutoStop,
                onPcmChunk = session::offerAudio,
            )
            withTimeout(SETUP_TIMEOUT_MS) { session.setupComplete.await() }
            diagnosticEvent(
                "gemini_live_setup_completed",
                mapOf(
                    "model" to GEMINI_TRANSCRIBE_LIVE_MODEL,
                    "candidate_languages" to languageCodes.joinToString(","),
                    "server_frame_type" to session.setupFrameType,
                    "setup_duration_ms" to
                        (SystemClock.elapsedRealtime() - connectedAt).toString(),
                ),
            )
        } catch (throwable: Throwable) {
            recorder.cancel()
            activeSession.compareAndSet(session, null)
            session.cancel()
            throw throwable
        }
    }

    override suspend fun stop(): String = withContext(Dispatchers.IO) {
        val session = activeSession.get() ?: error("No Gemini Live transcription is active.")
        try {
            val recording = recorder.stop()
            session.endAudio()
            val transcript = withTimeout(FINAL_RESULT_TIMEOUT_MS) { session.finalResult.await() }
            if (transcript.isBlank()) error("Gemini Live Transcribe returned an empty transcript.")
            diagnosticEvent(
                "gemini_live_transcribe_response",
                mapOf(
                    "model" to GEMINI_TRANSCRIBE_LIVE_MODEL,
                    "candidate_languages" to session.languageCodes.joinToString(","),
                    "captured_audio_ms" to recording.capturedDurationMs.toString(),
                    "streamed_audio_bytes" to session.streamedAudioBytes.toString(),
                    "streamed_audio_chunks" to session.streamedAudioChunks.toString(),
                ),
            )
            transcript
        } finally {
            activeSession.compareAndSet(session, null)
            session.close()
        }
    }

    override fun cancel() {
        recorder.cancel()
        activeSession.getAndSet(null)?.cancel()
    }

    private fun listenerFor(session: LiveSession) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            session.attach(webSocket)
            if (!webSocket.send(geminiLiveSetupPayload(session.languageCodes).toString())) {
                session.fail(IOException("Could not send Gemini Live setup."))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(session, text, "text")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleMessage(session, geminiLiveBinaryFrameText(bytes), "binary")
        }

        private fun handleMessage(session: LiveSession, text: String, frameType: String) {
            val payload = runCatching { JSONObject(text) }.getOrElse {
                session.fail(IOException("Gemini Live returned an invalid response.", it))
                return
            }
            payload.optJSONObject("error")?.let { errorPayload ->
                session.fail(
                    IOException(
                        errorPayload.optString("message")
                            .ifBlank { "Gemini Live returned an error." },
                    ),
                )
                return
            }
            if (payload.has("setupComplete")) session.markSetupComplete(frameType)
            payload.optJSONObject("serverContent")?.let(session::acceptServerContent)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            session.closed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            val suffix = response?.let { " (HTTP ${it.code})" }.orEmpty()
            session.fail(IOException("Gemini Live connection failed$suffix.", throwable))
        }
    }

    private class LiveSession(
        val languageCodes: List<String>,
        private val onPartialResult: (String) -> Unit,
    ) {
        val setupComplete = CompletableDeferred<Unit>()
        val finalResult = CompletableDeferred<String>()
        private val lock = Any()
        private val pendingAudio = ArrayDeque<ByteArray>()
        private var socket: WebSocket? = null
        private var setupReady = false
        private var audioEnded = false
        private var committedText = ""
        private var pendingAudioBytes = 0
        @Volatile var setupFrameType = "unknown"
            private set
        private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var delayedCompletion: Job? = null
        var streamedAudioBytes: Long = 0
            private set
        var streamedAudioChunks: Long = 0
            private set

        fun attach(webSocket: WebSocket) {
            synchronized(lock) { socket = webSocket }
        }

        fun offerAudio(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            synchronized(lock) {
                if (audioEnded || finalResult.isCompleted) return
                if (!setupReady) {
                    pendingAudio.addLast(bytes)
                    pendingAudioBytes += bytes.size
                    while (pendingAudioBytes > MAX_PENDING_AUDIO_BYTES && pendingAudio.isNotEmpty()) {
                        pendingAudioBytes -= pendingAudio.removeFirst().size
                    }
                    return
                }
                sendAudioLocked(bytes)
            }
        }

        fun markSetupComplete(frameType: String) {
            synchronized(lock) {
                if (setupReady) return
                setupReady = true
                setupFrameType = frameType
                while (pendingAudio.isNotEmpty()) {
                    val bytes = pendingAudio.removeFirst()
                    pendingAudioBytes -= bytes.size
                    sendAudioLocked(bytes)
                }
                if (audioEnded) sendAudioEndLocked()
            }
            setupComplete.complete(Unit)
        }

        fun endAudio() {
            synchronized(lock) {
                if (audioEnded) return
                audioEnded = true
                if (setupReady) sendAudioEndLocked()
                if (committedText.isNotBlank()) scheduleCommittedCompletionLocked()
            }
        }

        fun acceptServerContent(content: JSONObject) {
            val finalTranscription = content.optJSONObject("inputTranscription")
                ?: content.optJSONObject("input_transcription")
            val interimTranscription = content.optJSONObject("interimInputTranscription")
                ?: content.optJSONObject("interim_input_transcription")
            synchronized(lock) {
                finalTranscription?.let { transcription ->
                    val text = transcription.optString("text").trim()
                    if (text.isNotBlank()) {
                        committedText = mergeRecognitionTranscripts(committedText, text)
                    }
                }
                interimTranscription?.let { transcription ->
                    val interimText = transcription.optString("text").trim()
                    val displayText = mergeRecognitionTranscripts(committedText, interimText)
                    if (displayText.isNotBlank()) runCatching { onPartialResult(displayText) }
                }
                if (finalTranscription != null && committedText.isNotBlank()) {
                    runCatching { onPartialResult(committedText) }
                    if (audioEnded) {
                        delayedCompletion?.cancel()
                        completeLocked()
                    }
                } else if (
                    content.optBoolean("turnComplete") &&
                    audioEnded &&
                    committedText.isNotBlank()
                ) {
                    scheduleCommittedCompletionLocked()
                }
            }
        }

        fun fail(throwable: Throwable) {
            setupComplete.completeExceptionally(throwable)
            finalResult.completeExceptionally(throwable)
        }

        fun closed(code: Int, reason: String) {
            if (!finalResult.isCompleted) {
                fail(
                    IOException(
                        "Gemini Live connection closed before transcription completed " +
                            "($code${reason.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}).",
                    ),
                )
            }
        }

        fun close() {
            completionScope.coroutineContext[Job]?.cancel()
            synchronized(lock) { socket?.close(NORMAL_CLOSE_CODE, "Transcription complete") }
        }

        fun cancel() {
            val cancelled = IOException("Gemini Live transcription was cancelled.")
            fail(cancelled)
            completionScope.coroutineContext[Job]?.cancel()
            synchronized(lock) { socket?.cancel() }
        }

        private fun sendAudioLocked(bytes: ByteArray) {
            val message = geminiLiveAudioPayload(bytes).toString()
            if (socket?.send(message) != true) {
                fail(IOException("Could not stream audio to Gemini Live."))
                return
            }
            streamedAudioBytes += bytes.size
            streamedAudioChunks += 1
        }

        private fun sendAudioEndLocked() {
            if (socket?.send(geminiLiveAudioEndPayload().toString()) != true) {
                fail(IOException("Could not finish the Gemini Live audio stream."))
            }
        }

        private fun scheduleCommittedCompletionLocked() {
            delayedCompletion?.cancel()
            delayedCompletion = completionScope.launch {
                delay(FINAL_EVENT_SETTLE_MS)
                synchronized(lock) {
                    if (audioEnded && committedText.isNotBlank()) completeLocked()
                }
            }
        }

        private fun completeLocked() {
            finalResult.complete(committedText.trim())
        }
    }

    private companion object {
        const val LIVE_API_URL =
            "https://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val SETUP_TIMEOUT_MS = 20_000L
        const val FINAL_RESULT_TIMEOUT_MS = 30_000L
        const val MAX_PENDING_AUDIO_BYTES = 16_000 * 2 * 20
        const val FINAL_EVENT_SETTLE_MS = 250L
        const val NORMAL_CLOSE_CODE = 1000
    }
}

internal fun geminiLiveSetupPayload(languageCodes: List<String>): JSONObject = JSONObject()
    .put(
        "setup",
        JSONObject()
            .put("model", "models/$GEMINI_TRANSCRIBE_LIVE_MODEL")
            .put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("TEXT")),
            )
            .put(
                "inputAudioTranscription",
                JSONObject()
                    .put("languageCodes", JSONArray(languageCodes))
                    .put("mode", "VERBATIM"),
            ),
    )

internal fun geminiLiveAudioPayload(pcm: ByteArray): JSONObject = JSONObject()
    .put(
        "realtimeInput",
        JSONObject().put(
            "audio",
            JSONObject()
                .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                .put("mimeType", "audio/pcm;rate=16000"),
        ),
    )

internal fun geminiLiveAudioEndPayload(): JSONObject = JSONObject()
    .put("realtimeInput", JSONObject().put("audioStreamEnd", true))

internal fun geminiLiveBinaryFrameText(bytes: ByteString): String = bytes.utf8()
