package com.sayit.translator

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiTranslationProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
) : TranslationProvider {

    override suspend fun translate(
        apiKey: String,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        transcript: String,
        geminiModel: GeminiTranslationModel,
        serbianScript: SerbianScript,
    ): TranslationResult = withContext(Dispatchers.IO) {
        val prompt = translationPrompt(sourceLanguage, targetLanguage, transcript)
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
                    .put("responseJsonSchema", translationOutputSchema())
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "minimal"),
                    ),
            )

        val request = Request.Builder()
            .url("$GENERATE_CONTENT_URL/${geminiModel.id}:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val startedAt = SystemClock.elapsedRealtime()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            Log.i(
                TIMING_TAG,
                "stage=gemini_translation_http duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            val body = runCatching { JSONObject(bodyText) }.getOrElse {
                throw IOException("Gemini returned an invalid response.", it)
            }
            if (!response.isSuccessful) {
                val message = body.optJSONObject("error")?.optString("message").orEmpty()
                throw IOException(message.ifBlank { "Gemini API returned HTTP ${response.code}." })
            }

            val candidate = body.optJSONArray("candidates")?.optJSONObject(0)
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
            val translatedText = runCatching {
                JSONObject(structuredText).optString("translatedText").trim()
            }.getOrElse { throw IOException("Gemini returned invalid translation JSON.", it) }
            if (translatedText.isBlank()) throw IOException("Gemini returned an empty translation.")
            TranslationResult(sourceLanguage, targetLanguage, translatedText)
        }
    }

    private companion object {
        const val GENERATE_CONTENT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"
        const val TIMING_TAG = "SayItTiming"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
