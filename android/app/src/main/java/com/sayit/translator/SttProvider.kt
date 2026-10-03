package com.sayit.translator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

interface SttProvider {
    suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit)
    suspend fun stop(): String
    fun cancel()
}

internal fun mergeRecognitionTranscripts(committed: String, incoming: String): String {
    val stable = committed.trim()
    val addition = incoming.trim()
    if (stable.isBlank()) return addition
    if (addition.isBlank()) return stable

    val stableWords = stable.split(Regex("\\s+"))
    val additionWords = addition.split(Regex("\\s+"))
    if (
        stableWords.size >= MIN_RECOGNITION_OVERLAP_WORDS &&
        normalizedRecognitionText(stable) == normalizedRecognitionText(addition)
    ) {
        return stable
    }

    val maximumOverlap = minOf(stableWords.size, additionWords.size)
    val overlap = (maximumOverlap downTo MIN_RECOGNITION_OVERLAP_WORDS).firstOrNull { size ->
        val stableSuffix = stableWords.takeLast(size).joinToString(" ")
        val additionPrefix = additionWords.take(size).joinToString(" ")
        normalizedRecognitionText(stableSuffix) == normalizedRecognitionText(additionPrefix)
    } ?: 0
    return (stableWords + additionWords.drop(overlap)).joinToString(" ").trim()
}

private fun normalizedRecognitionText(text: String): String = text
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private const val MIN_RECOGNITION_OVERLAP_WORDS = 2

fun Context.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
