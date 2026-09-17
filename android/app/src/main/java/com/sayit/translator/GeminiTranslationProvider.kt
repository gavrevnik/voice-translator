package com.sayit.translator

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class GeminiTranslationProvider(
    private val client: OkHttpClient = geminiTranslationHttpClient(),
    private val diagnosticEvent: DiagnosticEvent = { _, _ -> },
) : TranslationProvider {

    override suspend fun translate(
        apiKey: String,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        transcript: String,
        geminiModel: GeminiTranslationModel,
        serbianScript: SerbianScript,
        liveSourceLanguage: String?,
    ): TranslationResult {
        val promptMode = if (liveSourceLanguage == null) {
            "standard_directional"
        } else {
            "detected_language_live"
        }
        val prompt = if (liveSourceLanguage == null) {
            translationPrompt(sourceLanguage, targetLanguage, transcript)
        } else {
            liveTranslationPrompt(liveSourceLanguage, targetLanguage, transcript)
        }
        val structured = generateStructuredTranslation(
            apiKey = apiKey,
            prompt = prompt,
            geminiModel = geminiModel,
            responseSchema = translationOutputSchema(),
            diagnosticFields = mapOf(
                "prompt_mode" to promptMode,
                "source_language" to sourceLanguage.code,
                "target_language" to targetLanguage.code,
                "input_characters" to transcript.length.toString(),
            ),
        )
        val translatedText = structured.optString("translatedText").trim()
        if (translatedText.isBlank()) throw IOException("Gemini returned an empty translation.")
        return TranslationResult(sourceLanguage, targetLanguage, translatedText)
    }

    private suspend fun generateStructuredTranslation(
        apiKey: String,
        prompt: TranslationPrompt,
        geminiModel: GeminiTranslationModel,
        responseSchema: JSONObject,
        diagnosticFields: Map<String, String>,
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    org.json.JSONArray().put(JSONObject().put("text", prompt.systemInstruction)),
                ),
            )
            .put(
                "contents",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            org.json.JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    prompt.userInput,
                                ),
                            ),
                        ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0)
                    .put("maxOutputTokens", 2_048)
                    .put("responseMimeType", "application/json")
                    .put("responseJsonSchema", responseSchema)
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "minimal"),
                    ),
            )

        val payloadText = payload.toString()
        val httpTrace = GeminiTranslationHttpTrace()
        val request = Request.Builder()
            .url("$GENERATE_CONTENT_URL/${geminiModel.id}:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
            .post(payloadText.toRequestBody(JSON_MEDIA_TYPE))
            .tag(GeminiTranslationHttpTrace::class.java, httpTrace)
            .build()

        val baseDiagnosticFields = diagnosticFields + mapOf(
            "model" to geminiModel.id,
            "payload_bytes" to payloadText.toByteArray(Charsets.UTF_8).size.toString(),
            "system_instruction_characters" to prompt.systemInstruction.length.toString(),
        )
        val startedAt = SystemClock.elapsedRealtime()
        val call = client.newCall(request)
        val response = try {
            call.execute()
        } catch (throwable: Throwable) {
            diagnosticEvent(
                "gemini_translation_http_failed",
                baseDiagnosticFields + httpTrace.snapshot() + mapOf(
                    "error_type" to throwable.javaClass.simpleName,
                    "error_message" to (throwable.message ?: "Unknown transport error"),
                ),
            )
            throw throwable
        }
        response.use {
            val bodyText = response.body?.string().orEmpty()
            val totalDurationMs = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                TIMING_TAG,
                "stage=gemini_translation_http duration_ms=$totalDurationMs",
            )
            diagnosticEvent(
                "gemini_translation_http_completed",
                baseDiagnosticFields + httpTrace.snapshot() + mapOf(
                    "http_status" to response.code.toString(),
                    "http_protocol" to response.protocol.toString(),
                    "response_bytes" to bodyText.toByteArray(Charsets.UTF_8).size.toString(),
                    "provider_total_ms" to totalDurationMs.toString(),
                ),
            )
            val body = runCatching { JSONObject(bodyText) }.getOrElse {
                throw IOException("Gemini returned an invalid response.", it)
            }
            if (!response.isSuccessful) {
                val message = body.optJSONObject("error")?.optString("message").orEmpty()
                throw IOException(message.ifBlank { "Gemini API returned HTTP ${response.code}." })
            }

            val candidate = body.optJSONArray("candidates")?.optJSONObject(0)
            val usage = body.optJSONObject("usageMetadata")
            diagnosticEvent(
                "gemini_translation_response_metadata",
                baseDiagnosticFields + mapOf(
                    "model_version" to body.optString("modelVersion", geminiModel.id),
                    "finish_reason" to candidate?.optString("finishReason").orEmpty()
                        .ifBlank { "not_reported" },
                    "prompt_tokens" to
                        (usage?.optInt("promptTokenCount", -1) ?: -1).toString(),
                    "candidate_tokens" to
                        (usage?.optInt("candidatesTokenCount", -1) ?: -1).toString(),
                    "total_tokens" to
                        (usage?.optInt("totalTokenCount", -1) ?: -1).toString(),
                    "cached_content_tokens" to
                        (usage?.optInt("cachedContentTokenCount", -1) ?: -1).toString(),
                ),
            )
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val structuredText = buildString {
                if (parts != null) {
                    for (index in 0 until parts.length()) {
                        append(parts.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }
            }.trim()
            if (structuredText.isBlank()) {
                val reason = candidate?.optString("finishReason").orEmpty()
                throw IOException(
                    if (reason.isBlank()) "Gemini returned no translation."
                    else "Gemini returned no translation ($reason).",
                )
            }
            runCatching { JSONObject(structuredText) }
                .getOrElse { throw IOException("Gemini returned invalid translation JSON.", it) }
        }
    }

    private companion object {
        const val GENERATE_CONTENT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"
        const val TIMING_TAG = "SayItTiming"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun geminiTranslationHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .eventListenerFactory { call ->
        GeminiTranslationEventListener(
            call.request().tag(GeminiTranslationHttpTrace::class.java),
        )
    }
    .build()

private class GeminiTranslationEventListener(
    private val trace: GeminiTranslationHttpTrace?,
) : EventListener() {
    override fun callStart(call: Call) {
        trace?.callStartedAtMs = SystemClock.elapsedRealtime()
    }

    override fun dnsStart(call: Call, domainName: String) {
        trace?.dnsStartedAtMs = SystemClock.elapsedRealtime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        trace?.apply {
            dnsEndedAtMs = SystemClock.elapsedRealtime()
            resolvedAddressCount = inetAddressList.size
        }
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        trace?.apply {
            connectObserved = true
            connectStartedAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun secureConnectStart(call: Call) {
        trace?.tlsStartedAtMs = SystemClock.elapsedRealtime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        trace?.tlsEndedAtMs = SystemClock.elapsedRealtime()
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        trace?.connectEndedAtMs = SystemClock.elapsedRealtime()
    }

    override fun requestHeadersStart(call: Call) {
        trace?.requestStartedAtMs = SystemClock.elapsedRealtime()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        trace?.requestHeadersEndedAtMs = SystemClock.elapsedRealtime()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        trace?.apply {
            requestEndedAtMs = SystemClock.elapsedRealtime()
            requestBodyBytes = byteCount
        }
    }

    override fun responseHeadersStart(call: Call) {
        trace?.responseHeadersStartedAtMs = SystemClock.elapsedRealtime()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        trace?.responseHeadersEndedAtMs = SystemClock.elapsedRealtime()
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        trace?.apply {
            responseBodyEndedAtMs = SystemClock.elapsedRealtime()
            responseBodyBytes = byteCount
        }
    }

    override fun callEnd(call: Call) {
        trace?.callEndedAtMs = SystemClock.elapsedRealtime()
    }

    override fun callFailed(call: Call, ioe: IOException) {
        trace?.callEndedAtMs = SystemClock.elapsedRealtime()
    }
}

internal class GeminiTranslationHttpTrace {
    var callStartedAtMs = UNSET
    var callEndedAtMs = UNSET
    var dnsStartedAtMs = UNSET
    var dnsEndedAtMs = UNSET
    var connectStartedAtMs = UNSET
    var connectEndedAtMs = UNSET
    var tlsStartedAtMs = UNSET
    var tlsEndedAtMs = UNSET
    var requestStartedAtMs = UNSET
    var requestHeadersEndedAtMs = UNSET
    var requestEndedAtMs = UNSET
    var responseHeadersStartedAtMs = UNSET
    var responseHeadersEndedAtMs = UNSET
    var responseBodyEndedAtMs = UNSET
    var requestBodyBytes = -1L
    var responseBodyBytes = -1L
    var resolvedAddressCount = -1
    var connectObserved = false

    fun snapshot(nowMs: Long = SystemClock.elapsedRealtime()): Map<String, String> {
        val now = nowMs
        val effectiveCallEnd = callEndedAtMs.takeIfObserved() ?: now
        val effectiveRequestEnd = requestEndedAtMs.takeIfObserved()
            ?: requestHeadersEndedAtMs.takeIfObserved()
        return mapOf(
            "http_call_total_ms" to duration(callStartedAtMs, effectiveCallEnd),
            "dns_ms" to duration(dnsStartedAtMs, dnsEndedAtMs),
            "connect_ms" to duration(connectStartedAtMs, connectEndedAtMs),
            "tls_ms" to duration(tlsStartedAtMs, tlsEndedAtMs),
            "request_send_ms" to duration(requestStartedAtMs, effectiveRequestEnd),
            "wait_for_response_headers_ms" to
                duration(effectiveRequestEnd, responseHeadersStartedAtMs),
            "ttfb_from_call_start_ms" to
                duration(callStartedAtMs, responseHeadersStartedAtMs),
            "response_headers_ms" to
                duration(responseHeadersStartedAtMs, responseHeadersEndedAtMs),
            "response_read_ms" to
                duration(responseHeadersStartedAtMs, responseBodyEndedAtMs),
            "connection_reused" to when {
                connectObserved -> "false"
                responseHeadersStartedAtMs != UNSET -> "true"
                else -> NOT_OBSERVED
            },
            "resolved_address_count" to resolvedAddressCount.observedValue(),
            "request_body_bytes" to requestBodyBytes.observedValue(),
            "response_body_bytes" to responseBodyBytes.observedValue(),
        )
    }

    private fun duration(startMs: Long?, endMs: Long?): String =
        if (startMs == null || endMs == null || startMs == UNSET || endMs == UNSET) {
            NOT_OBSERVED
        } else {
            (endMs - startMs).coerceAtLeast(0L).toString()
        }

    private fun Long.takeIfObserved(): Long? = takeIf { it != UNSET }

    private fun Long.observedValue(): String =
        if (this < 0L) NOT_OBSERVED else toString()

    private fun Int.observedValue(): String =
        if (this < 0) NOT_OBSERVED else toString()

    private companion object {
        const val UNSET = -1L
        const val NOT_OBSERVED = "not_observed"
    }
}
