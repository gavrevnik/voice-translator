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
 * Keeps a diagnostic trace for the most recent normal voice cycle or the complete Conversation
 * Live session between its Start and Stop button presses.
 *
 * Normal-cycle traces exclude text. Live traces intentionally include recognized and translated
 * text to make language routing debuggable. Speech audio and API keys are never stored.
 */
internal class TurnDiagnosticsRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val diagnosticsDirectory = File(appContext.filesDir, "diagnostics")
    private val lastCycleFile = File(diagnosticsDirectory, "last-cycle.log")
    private var active = false
    private var traceMode = DiagnosticTraceMode.SINGLE_CYCLE
    private var startedAtElapsedMs = 0L
    private var cycleStartedAtElapsedMs = 0L
    private var liveCycleIndex = 0
    private var liveCycleActive = false

    fun begin(fields: Map<String, String>) = synchronized(lock) {
        traceMode = DiagnosticTraceMode.SINGLE_CYCLE
        liveCycleIndex = 0
        liveCycleActive = false
        active = true
        writeHeader("Say it — last voice cycle diagnostics", fields, includesText = false)
        appendLine(eventLine("cycle_started", emptyMap()))
    }

    fun beginLiveSession(fields: Map<String, String>) = synchronized(lock) {
        traceMode = DiagnosticTraceMode.LIVE_SESSION
        liveCycleIndex = 0
        liveCycleActive = false
        active = true
        writeHeader("Say it — Conversation Live session diagnostics", fields, includesText = true)
        appendLine(eventLine("live_session_started", emptyMap()))
    }

    fun beginLiveCycle(fields: Map<String, String>) = synchronized(lock) {
        if (!active || traceMode != DiagnosticTraceMode.LIVE_SESSION) return@synchronized
        if (liveCycleActive) {
            appendLine(eventLine("cycle_finished", mapOf("outcome" to "superseded")))
        }
        liveCycleIndex += 1
        liveCycleActive = true
        cycleStartedAtElapsedMs = SystemClock.elapsedRealtime()
        appendLine("")
        appendLine(eventLine("cycle_started", fields))
    }

    fun event(name: String, fields: Map<String, String> = emptyMap()) = synchronized(lock) {
        if (!active) return@synchronized
        appendLine(eventLine(name, fields))
    }

    fun finish(outcome: String) = synchronized(lock) {
        if (!active) return@synchronized
        if (traceMode == DiagnosticTraceMode.LIVE_SESSION) {
            finishLiveSessionLocked(outcome)
            return@synchronized
        }
        appendLine(eventLine("cycle_finished", mapOf("outcome" to outcome)))
        active = false
    }

    fun finishLiveCycle(outcome: String) = synchronized(lock) {
        if (
            !active ||
            traceMode != DiagnosticTraceMode.LIVE_SESSION ||
            !liveCycleActive
        ) {
            return@synchronized
        }
        appendLine(eventLine("cycle_finished", mapOf("outcome" to outcome)))
        liveCycleActive = false
    }

    fun finishLiveSession(outcome: String) = synchronized(lock) {
        if (!active || traceMode != DiagnosticTraceMode.LIVE_SESSION) return@synchronized
        finishLiveSessionLocked(outcome)
    }

    fun fail(throwable: Throwable) = synchronized(lock) {
        if (!active) return@synchronized
        val errorFields = mapOf(
            "error_type" to throwable.javaClass.simpleName,
            "error_message" to (throwable.message ?: "Unknown error"),
        )
        if (traceMode == DiagnosticTraceMode.LIVE_SESSION) {
            if (liveCycleActive) {
                appendLine(eventLine("cycle_failed", errorFields))
                liveCycleActive = false
            }
            appendLine(eventLine("live_session_failed", errorFields))
        } else {
            appendLine(eventLine("cycle_failed", errorFields))
        }
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
        val liveSession = lastFileIsLiveSession()
        val filePrefix = if (liveSession) {
            "say-it-live-session"
        } else {
            "say-it-last-cycle"
        }
        val sharedFile = File(sharedDirectory, "$filePrefix-$timestamp.log")
        lastCycleFile.copyTo(sharedFile, overwrite = true)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            sharedFile,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                if (liveSession) {
                    "Say it — Conversation Live session diagnostics"
                } else {
                    "Say it — last voice cycle diagnostics"
                },
            )
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
        val cycleFields = if (
            traceMode == DiagnosticTraceMode.LIVE_SESSION && liveCycleActive
        ) {
            " cycle=$liveCycleIndex cycle_elapsed_ms=" +
                (SystemClock.elapsedRealtime() - cycleStartedAtElapsedMs).coerceAtLeast(0L)
        } else {
            ""
        }
        val suffix = fields.toSortedMap().entries.joinToString(separator = "") { (key, value) ->
            " ${sanitizeKey(key)}=${sanitize(value)}"
        }
        return "elapsed_ms=$elapsedMs$cycleFields event=${sanitizeKey(name)}$suffix"
    }

    private fun writeHeader(
        title: String,
        fields: Map<String, String>,
        includesText: Boolean,
    ) {
        diagnosticsDirectory.mkdirs()
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        cycleStartedAtElapsedMs = startedAtElapsedMs
        lastCycleFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(title)
            writer.appendLine(
                if (includesText) {
                    "privacy=Recognized and translated text are included for Live diagnostics; " +
                        "audio and API keys are not included."
                } else {
                    "privacy=No audio, recognized text, translation text, or API keys are included."
                },
            )
            writer.appendLine(
                if (includesText) {
                    "retention=Only the latest Live session is kept; the next Live start overwrites it."
                } else {
                    "retention=Only the latest normal voice cycle is kept."
                },
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
        }
    }

    private fun finishLiveSessionLocked(outcome: String) {
        if (liveCycleActive) {
            appendLine(eventLine("cycle_finished", mapOf("outcome" to "interrupted_by_user")))
            liveCycleActive = false
        }
        appendLine(eventLine("live_session_finished", mapOf("outcome" to outcome)))
        active = false
    }

    private fun lastFileIsLiveSession(): Boolean = runCatching {
        lastCycleFile.useLines { lines ->
            lines.firstOrNull()?.contains("Conversation Live session") == true
        }
    }.getOrDefault(false)

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
        const val MAX_VALUE_LENGTH = 4_000
        val SHARE_FILE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}

private enum class DiagnosticTraceMode {
    SINGLE_CYCLE,
    LIVE_SESSION,
}
