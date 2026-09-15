package com.sayit.translator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface TranslationProvider {
    suspend fun translate(
        apiKey: String,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        transcript: String,
        model: TranslationModel,
        geminiModel: GeminiTranslationModel,
        serbianScript: SerbianScript,
    ): TranslationResult
}

class OpenAiTranslationProvider(
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
        model: TranslationModel,
        geminiModel: GeminiTranslationModel,
        serbianScript: SerbianScript,
    ): TranslationResult = withContext(Dispatchers.IO) {
        val prompt = translationPrompt(sourceLanguage, targetLanguage, transcript)
        val payload = JSONObject()
            .put("model", model.id)
            .put("store", false)
            .put("instructions", prompt.systemInstruction)
            .put("input", prompt.userInput)
            .put("reasoning", JSONObject().put("effort", "none"))
            .put(
                "text",
                JSONObject()
                    .put("verbosity", "low")
                    .put("format", outputFormat()),
            )

        val request = Request.Builder()
            .url(RESPONSES_URL)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val startedAt = SystemClock.elapsedRealtime()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.i(
                TIMING_TAG,
                "stage=openai_http duration_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                    "server_processing_ms=${response.header("openai-processing-ms") ?: "unavailable"}",
            )
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { "OpenAI API returned HTTP ${response.code}." })
            }

            val structuredText = extractOutputText(JSONObject(body))
            val translatedText = JSONObject(structuredText).optString("translatedText").trim()
            if (translatedText.isBlank()) throw IOException("OpenAI returned an empty translation.")
            TranslationResult(sourceLanguage, targetLanguage, translatedText)
        }
    }

    private fun outputFormat(): JSONObject = JSONObject()
        .put("type", "json_schema")
        .put("name", "translation_result")
        .put("strict", true)
        .put("schema", translationOutputSchema())

    private fun extractOutputText(response: JSONObject): String {
        response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = response.optJSONArray("output") ?: throw IOException("OpenAI response has no output.")
        for (outputIndex in 0 until output.length()) {
            val content = output.optJSONObject(outputIndex)?.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val item = content.optJSONObject(contentIndex) ?: continue
                if (item.optString("type") == "output_text") {
                    item.optString("text").takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        throw IOException("OpenAI response contains no translation text.")
    }

    private companion object {
        const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        const val TIMING_TAG = "SayItTiming"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
