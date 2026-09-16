package com.sayit.translator

import android.content.Context
import io.github.marcosholgado.translatekit.ModelSpec
import io.github.marcosholgado.translatekit.TranslateKit
import io.github.marcosholgado.translatekit.TranslationModel as NativeTranslationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.BreakIterator
import java.util.Locale

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
            offlineOpusInputs(
                family = family,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                serbianScript = serbianScript,
                transcript = transcript,
            ).joinToString(" ") { input ->
                runtimeModel.translate(input, isHtml = false).text.trim()
            }.trim()
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

internal fun offlineOpusInputs(
    family: OfflineOpusFamily,
    sourceLanguage: AppLanguage,
    targetLanguage: AppLanguage,
    serbianScript: SerbianScript,
    transcript: String,
): List<String> {
    val text = transcript.trim()
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getSentenceInstance(
        Locale.forLanguageTag(sourceLanguage.bcp47),
    ).apply { setText(text) }
    val sentences = buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) add(sentence)
            start = end
            end = iterator.next()
        }
    }.ifEmpty { listOf(text) }
    return sentences.map { sentence ->
        offlineOpusInput(family, targetLanguage, serbianScript, sentence)
    }
}
