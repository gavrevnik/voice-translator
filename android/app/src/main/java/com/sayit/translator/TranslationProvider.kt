package com.sayit.translator

interface TranslationProvider {
    suspend fun translate(
        apiKey: String,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        transcript: String,
        geminiModel: GeminiTranslationModel,
        serbianScript: SerbianScript,
    ): TranslationResult
}
