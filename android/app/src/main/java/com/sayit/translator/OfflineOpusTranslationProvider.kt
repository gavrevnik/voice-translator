package com.sayit.translator

import android.content.Context
import io.github.marcosholgado.translatekit.ModelSpec
import io.github.marcosholgado.translatekit.TranslateKit
import io.github.marcosholgado.translatekit.TranslationModel as NativeTranslationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OfflineOpusTranslationProvider(
    context: Context,
    private val modelManager: OfflineModelManager,
    private val family: OfflineOpusFamily,
) : TranslationProvider {
    private val appContext = context.applicationContext
    private var loadedModel: NativeTranslationModel? = null

    override suspend fun translate(
        apiKey: String,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage,
        transcript: String,
        model: TranslationModel,
        geminiModel: GeminiTranslationModel,
        serbianScript: SerbianScript,
    ): TranslationResult = withContext(Dispatchers.IO) {
        require(modelManager.supports(sourceLanguage, targetLanguage)) {
            "${modelManager.manifest.displayName} does not support this language pair. " +
                "Choose a compatible offline or cloud model."
        }
        val translation = synchronized(this@OfflineOpusTranslationProvider) {
            val runtimeModel = loadedModel ?: loadModel().also { loadedModel = it }
            runtimeModel.translate(
                offlineOpusInput(family, targetLanguage, serbianScript, transcript),
                isHtml = false,
            ).text.trim()
        }
        check(translation.isNotBlank()) { "Offline OPUS returned an empty translation." }
        TranslationResult(sourceLanguage, targetLanguage, translation)
    }

    fun close() {
        synchronized(this) {
            loadedModel?.close()
            loadedModel = null
        }
    }

    private fun loadModel(): NativeTranslationModel {
        val files = modelManager.installedFiles()
        TranslateKit.init(appContext)
        check(TranslateKit.isInitialized()) {
            "Offline translation runtime is unavailable on this device. An arm64 device is required."
        }
        return TranslateKit.loadModel(
            ModelSpec(
                sourceLang = family.groupCode,
                targetLang = family.groupCode,
                modelPath = files.model.absolutePath,
                vocabPaths = listOf(files.sourceVocab.absolutePath, files.targetVocab.absolutePath),
                shortlistPath = files.shortlist.absolutePath,
                configYaml = files.config.absolutePath,
                numWorkers = 1,
            ),
        )
    }
}

enum class OfflineOpusFamily(val groupCode: String) {
    SLAVIC("sla"),
    INDO_EUROPEAN("ine"),
}

internal fun offlineOpusInput(
    family: OfflineOpusFamily,
    targetLanguage: AppLanguage,
    serbianScript: SerbianScript,
    transcript: String,
): String {
    val targetToken = when (family) {
        OfflineOpusFamily.SLAVIC -> when (targetLanguage) {
            AppLanguage.RUSSIAN -> ">>rus<<"
            AppLanguage.SERBIAN -> serbianScript.targetToken
            AppLanguage.CROATIAN -> ">>hrv<<"
            else -> error("Unsupported Slavic target language: ${targetLanguage.canonicalName}")
        }
        OfflineOpusFamily.INDO_EUROPEAN -> when (targetLanguage) {
            AppLanguage.RUSSIAN -> ">>rus<<"
            AppLanguage.SPANISH -> ">>spa<<"
            AppLanguage.ROMANIAN -> ">>ron<<"
            else -> error(
                "Unsupported Indo-European target language: ${targetLanguage.canonicalName}",
            )
        }
    }
    return "$targetToken ${transcript.trim()}"
}
