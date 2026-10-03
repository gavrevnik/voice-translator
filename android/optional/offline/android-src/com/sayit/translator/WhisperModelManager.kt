package com.sayit.translator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val WHISPER_MODEL_MANIFEST_ID = "whisper-large-v3-turbo-q4_0"

data class WhisperModelManifest(
    val id: String,
    val version: String,
    val displayName: String,
    val filename: String,
    val downloadUrl: String,
    val sha256: String,
    val downloadSize: Long,
    val installedSize: Long,
    val runtimeType: String,
    val runtimeVersion: String,
) {
    val downloadSizeLabel: String get() = decimalMegabytes(downloadSize)
    val installedSizeLabel: String get() = decimalMegabytes(installedSize)

    companion object {
        fun load(context: Context): WhisperModelManifest {
            val raw = context.assets.open("whisper_models.json").bufferedReader().use { it.readText() }
            val catalog = JSONObject(raw)
            check(catalog.getString("defaultModelId") == WHISPER_MODEL_MANIFEST_ID) {
                "Unexpected default Whisper model."
            }
            val models = catalog.getJSONArray("models")
            check(models.length() == 1) { "Whisper catalog must contain only the Large model." }
            return parse(models.getJSONObject(0)).also { manifest ->
                check(manifest.id == WHISPER_MODEL_MANIFEST_ID) {
                    "Whisper manifest is missing $WHISPER_MODEL_MANIFEST_ID."
                }
            }
        }

        private fun parse(json: JSONObject): WhisperModelManifest {
            val id = json.getString("id")
            val overrideUrl = if (id == WHISPER_MODEL_MANIFEST_ID) {
                BuildConfig.OFFLINE_WHISPER_MODEL_URL
            } else {
                ""
            }
            return WhisperModelManifest(
                id = id,
                version = json.getString("version"),
                displayName = json.getString("displayName"),
                filename = json.getString("filename"),
                downloadUrl = overrideUrl.trim()
                    .ifBlank { json.getString("downloadUrl") },
                sha256 = json.getString("sha256"),
                downloadSize = json.getLong("downloadSize"),
                installedSize = json.getLong("installedSize"),
                runtimeType = json.getString("runtimeType"),
                runtimeVersion = json.getString("runtimeVersion"),
            )
        }

        private fun decimalMegabytes(bytes: Long): String =
            String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    }
}

class WhisperModelManager(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val appContext = context.applicationContext
    val manifest: WhisperModelManifest = WhisperModelManifest.load(appContext)

    init {
        LEGACY_MODEL_DIRECTORIES.forEach { relativePath ->
            File(appContext.filesDir, relativePath).deleteRecursively()
        }
    }

    private val vadDirectory = File(appContext.filesDir, "offline-models/$VAD_MODEL_ID")
    private val vadModelFile = File(vadDirectory, VAD_MODEL_FILENAME)
    @Volatile private var vadModelReady = false
    private val _status = MutableStateFlow(inspectInstallation())
    val status: StateFlow<OfflineModelStatus> = _status.asStateFlow()

    suspend fun downloadAndInstall() = withContext(Dispatchers.IO) {
        val modelRoot = modelRoot(manifest)
        val installDirectory = installDirectory(manifest)
        val download = File(modelRoot, "${manifest.version}.download")
        val staging = File(modelRoot, "${manifest.version}.staging")
        try {
            modelRoot.mkdirs()
            download.delete()
            staging.deleteRecursively()
            _status.value = OfflineModelStatus.Downloading(0f)

            val request = Request.Builder().url(manifest.downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Whisper model download returned HTTP ${response.code}.")
                }
                val body = response.body ?: throw IOException("Whisper model download returned no data.")
                val total = body.contentLength().takeIf { it > 0 } ?: manifest.downloadSize
                var received = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(download).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            val percent = ((received * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _status.value = OfflineModelStatus.Downloading(percent / 100f)
                            }
                        }
                    }
                }
            }

            if (download.length() != manifest.downloadSize) {
                throw IOException("Downloaded Whisper model has an invalid size.")
            }
            if (!OfflineModelIntegrity.matchesChecksum(download, manifest.sha256)) {
                throw IOException("Downloaded Whisper model checksum mismatch.")
            }

            staging.mkdirs()
            val stagedModel = File(staging, manifest.filename)
            if (!download.renameTo(stagedModel)) {
                download.copyTo(stagedModel, overwrite = true)
                download.delete()
            }
            installDirectory.deleteRecursively()
            if (!staging.renameTo(installDirectory)) {
                throw IOException("Could not activate the downloaded Whisper model.")
            }
            _status.value = OfflineModelStatus.Installed(manifest.installedSizeLabel)
        } catch (error: Exception) {
            download.delete()
            staging.deleteRecursively()
            _status.value = OfflineModelStatus.Error(
                error.message ?: "Whisper model installation failed.",
            )
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        modelRoot(manifest).deleteRecursively()
        _status.value = OfflineModelStatus.NotInstalled
    }

    fun installedModel(): File {
        val installDirectory = installDirectory(manifest)
        val modelFile = modelFile(manifest)
        val validationError = validateInstallation()
        if (validationError != null) {
            _status.value = if (installDirectory.exists()) {
                OfflineModelStatus.Invalid(validationError)
            } else {
                OfflineModelStatus.NotInstalled
            }
            error(validationError)
        }
        return modelFile
    }

    fun installedVadModel(): File {
        if (vadModelReady) return vadModelFile
        synchronized(this) {
            if (vadModelReady) return vadModelFile
            if (
                !vadModelFile.isFile ||
                vadModelFile.length() != VAD_MODEL_SIZE ||
                !OfflineModelIntegrity.matchesChecksum(vadModelFile, VAD_MODEL_SHA256)
            ) {
                vadDirectory.mkdirs()
                val staging = File(vadDirectory, "$VAD_MODEL_FILENAME.staging")
                staging.delete()
                appContext.assets.open(VAD_MODEL_ASSET).use { input ->
                    FileOutputStream(staging).buffered().use { output -> input.copyTo(output) }
                }
                check(staging.length() == VAD_MODEL_SIZE) { "Bundled Whisper VAD model has an invalid size." }
                check(OfflineModelIntegrity.matchesChecksum(staging, VAD_MODEL_SHA256)) {
                    "Bundled Whisper VAD model checksum mismatch."
                }
                vadModelFile.delete()
                check(staging.renameTo(vadModelFile)) { "Could not install the bundled Whisper VAD model." }
            }
            vadModelReady = true
            return vadModelFile
        }
    }

    private fun inspectInstallation(): OfflineModelStatus {
        val installDirectory = installDirectory(manifest)
        if (!installDirectory.exists()) return OfflineModelStatus.NotInstalled
        val error = validateInstallation()
        return if (error == null) {
            OfflineModelStatus.Installed(manifest.installedSizeLabel)
        } else {
            OfflineModelStatus.Invalid(error)
        }
    }

    private fun validateInstallation(): String? {
        val modelFile = modelFile(manifest)
        if (!modelFile.isFile) return "Whisper model is not installed."
        if (modelFile.length() != manifest.installedSize) {
            return "Whisper model has an invalid size."
        }
        if (!OfflineModelIntegrity.matchesChecksum(modelFile, manifest.sha256)) {
            return "Whisper model checksum mismatch."
        }
        return null
    }

    private fun modelRoot(manifest: WhisperModelManifest): File =
        File(appContext.filesDir, "offline-models/${manifest.id}")

    private fun installDirectory(manifest: WhisperModelManifest): File =
        File(modelRoot(manifest), manifest.version)

    private fun modelFile(manifest: WhisperModelManifest): File =
        File(installDirectory(manifest), manifest.filename)

    private companion object {
        val LEGACY_MODEL_DIRECTORIES = listOf(
            "offline-models/whisper-base-q5_1",
            "offline-models/whisper-small-q5_1",
        )
        const val VAD_MODEL_ID = "whisper-vad-silero-v6.2.0"
        const val VAD_MODEL_ASSET = "ggml-silero-v6.2.0.bin"
        const val VAD_MODEL_FILENAME = "ggml-silero-v6.2.0.bin"
        const val VAD_MODEL_SIZE = 885_098L
        const val VAD_MODEL_SHA256 =
            "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"
    }
}
