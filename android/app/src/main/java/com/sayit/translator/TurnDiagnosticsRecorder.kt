package com.sayit.translator

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal typealias DiagnosticEvent = (String, Map<String, String>) -> Unit

/**
 * Keeps a privacy-safe diagnostic trace for the most recent voice cycle.
 *
 * Speech audio, source text, translated text, and API keys are deliberately never accepted by
 * this class. Callers should pass only engine names, timings, counts, and safe error details.
 */
internal class TurnDiagnosticsRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val diagnosticsDirectory = File(appContext.filesDir, "diagnostics")
    private val lastCycleFile = File(diagnosticsDirectory, "last-cycle.log")
    private var active = false
    private var startedAtElapsedMs = 0L

    fun begin(fields: Map<String, String>) = synchronized(lock) {
        diagnosticsDirectory.mkdirs()
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        active = true
        lastCycleFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Say it — last voice cycle diagnostics")
            writer.appendLine(
                "privacy=No audio, recognized text, translation text, or API keys are included.",
            )
            writer.appendLine("session_started_utc=${Instant.now()}")
            writer.appendLine("app_version=${BuildConfig.VERSION_NAME}")
            writer.appendLine("app_version_code=${BuildConfig.VERSION_CODE}")
            writer.appendLine("package=${BuildConfig.APPLICATION_ID}")
            writer.appendLine("device=${sanitize(Build.MANUFACTURER)} ${sanitize(Build.MODEL)}")
            writer.appendLine("android=${sanitize(Build.VERSION.RELEASE)} api=${Build.VERSION.SDK_INT}")
            writer.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",") { sanitize(it) }}")
            fields.toSortedMap().forEach { (key, value) ->
                writer.appendLine("${sanitizeKey(key)}=${sanitize(value)}")
            }
            writer.appendLine()
            writer.appendLine("events:")
            writer.appendLine(eventLine("cycle_started", emptyMap()))
        }
    }

    fun event(name: String, fields: Map<String, String> = emptyMap()) = synchronized(lock) {
        if (!active) return@synchronized
        appendLine(eventLine(name, fields))
    }

    fun finish(outcome: String) = synchronized(lock) {
        if (!active) return@synchronized
        appendLine(eventLine("cycle_finished", mapOf("outcome" to outcome)))
        active = false
    }

    fun fail(throwable: Throwable) = synchronized(lock) {
        if (!active) return@synchronized
        appendLine(
            eventLine(
                "cycle_failed",
                mapOf(
                    "error_type" to throwable.javaClass.simpleName,
                    "error_message" to (throwable.message ?: "Unknown error"),
                ),
            ),
        )
        active = false
    }

    fun hasLastCycle(): Boolean = synchronized(lock) {
        lastCycleFile.isFile && lastCycleFile.length() > 0L
    }

    fun createShareIntent(): Intent = synchronized(lock) {
        check(hasLastCycle()) { "Complete a voice cycle before exporting diagnostics." }
        val sharedDirectory = File(appContext.cacheDir, "shared-diagnostics").apply { mkdirs() }
        sharedDirectory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        val timestamp = SHARE_FILE_TIME_FORMATTER.format(Instant.now())
        val sharedFile = File(sharedDirectory, "say-it-last-cycle-$timestamp.log")
        lastCycleFile.copyTo(sharedFile, overwrite = true)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            sharedFile,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Say it — last voice cycle diagnostics")
            clipData = ClipData.newUri(
                appContext.contentResolver,
                "Say it diagnostics",
                uri,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun eventLine(name: String, fields: Map<String, String>): String {
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
        val suffix = fields.toSortedMap().entries.joinToString(separator = "") { (key, value) ->
            " ${sanitizeKey(key)}=${sanitize(value)}"
        }
        return "elapsed_ms=$elapsedMs event=${sanitizeKey(name)}$suffix"
    }

    private fun appendLine(line: String) {
        lastCycleFile.appendText("$line\n", Charsets.UTF_8)
    }

    private fun sanitizeKey(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        .take(MAX_KEY_LENGTH)

    private fun sanitize(value: String): String {
        val singleLine = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(Regex("(?i)\\b(?:sk-|gsk_)[A-Za-z0-9_-]{8,}"), "[REDACTED]")
            .replace(Regex("\\bAIza[A-Za-z0-9_-]{12,}"), "[REDACTED]")
        return singleLine.take(MAX_VALUE_LENGTH).ifBlank { "-" }
    }

    private companion object {
        const val MAX_KEY_LENGTH = 80
        const val MAX_VALUE_LENGTH = 300
        val SHARE_FILE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
