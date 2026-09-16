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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class OfflineModelFileSpec(
    val name: String,
    val size: Long,
    val sha256: String,
)

data class OfflineModelManifest(
    val id: String,
    val version: String,
    val displayName: String,
    val downloadUrl: String,
    val sha256: String,
    val downloadSize: Long,
    val installedSize: Long,
    val supportedDirections: Set<String>,
    val supportedScripts: Set<String>,
    val runtimeType: String,
    val runtimeVersion: String,
    val modelFile: String,
    val requiredFiles: Set<String>,
    val files: List<OfflineModelFileSpec>,
) {
    fun supports(source: AppLanguage, target: AppLanguage): Boolean =
        "${source.code}-${target.code}" in supportedDirections

    val downloadSizeLabel: String get() = decimalMegabytes(downloadSize)
    val installedSizeLabel: String get() = decimalMegabytes(installedSize)

    companion object {
        fun load(
            context: Context,
            assetName: String,
            overrideDownloadUrl: String = "",
        ): OfflineModelManifest {
            val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val fileArray = json.getJSONArray("files")
            val files = buildList {
                for (index in 0 until fileArray.length()) {
                    val file = fileArray.getJSONObject(index)
                    add(
                        OfflineModelFileSpec(
                            name = file.getString("name"),
                            size = file.getLong("size"),
                            sha256 = file.getString("sha256"),
                        ),
                    )
                }
            }
            return OfflineModelManifest(
                id = json.getString("id"),
                version = json.getString("version"),
                displayName = json.getString("displayName"),
                downloadUrl = overrideDownloadUrl.trim()
                    .ifBlank { json.getString("downloadUrl") },
                sha256 = json.getString("sha256"),
                downloadSize = json.getLong("downloadSize"),
                installedSize = json.getLong("installedSize"),
                supportedDirections = json.stringSet("supportedDirections"),
                supportedScripts = json.stringSet("supportedScripts"),
                runtimeType = json.getString("runtimeType"),
                runtimeVersion = json.getString("runtimeVersion"),
                modelFile = json.getString("modelFile"),
                requiredFiles = json.stringSet("requiredFiles"),
                files = files,
            )
        }

        private fun JSONObject.stringSet(name: String): Set<String> {
            val values = getJSONArray(name)
            return buildSet {
                for (index in 0 until values.length()) add(values.getString(index))
            }
        }

        private fun decimalMegabytes(bytes: Long): String =
            String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    }
}

sealed interface OfflineModelStatus {
    data object NotInstalled : OfflineModelStatus
    data class Downloading(val progress: Float) : OfflineModelStatus
    data class Installed(val installedSizeLabel: String) : OfflineModelStatus
    data class Invalid(val reason: String) : OfflineModelStatus
    data class Error(val reason: String) : OfflineModelStatus
}

data class OfflineModelFiles(
    val model: File,
    val sourceVocab: File,
    val targetVocab: File,
    val shortlist: File,
    val config: File,
)

internal object OfflineModelIntegrity {
    fun validate(directory: File, manifest: OfflineModelManifest): String? {
        if (!directory.isDirectory) return "Offline model is not installed."
        for (name in manifest.requiredFiles) {
            if (!File(directory, name).isFile) return "Model file is missing: $name"
        }
        for (expected in manifest.files) {
            val file = File(directory, expected.name)
            if (file.length() != expected.size) {
                return "Model file has an invalid size: ${expected.name}"
            }
            if (!matchesChecksum(file, expected.sha256)) {
                return "Model file checksum mismatch: ${expected.name}"
            }
        }
        return null
    }

    fun matchesChecksum(file: File, expected: String): Boolean =
        sha256(file).equals(expected, ignoreCase = true)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class OfflineModelManager(
    context: Context,
    assetName: String = "offline_models.json",
    overrideDownloadUrl: String = "",
    retiredModelIds: Set<String> = emptySet(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val appContext = context.applicationContext
    val manifest: OfflineModelManifest = OfflineModelManifest.load(
        appContext,
        assetName,
        overrideDownloadUrl,
    )
    private val modelRoot = File(appContext.filesDir, "offline-models/${manifest.id}")
    private val installDirectory = File(modelRoot, manifest.version)
    private val _status = MutableStateFlow(inspectInstallation())
    val status: StateFlow<OfflineModelStatus> = _status.asStateFlow()

    init {
        retiredModelIds
            .filter { it != manifest.id && it.matches(Regex("[A-Za-z0-9._-]+")) }
            .forEach { retiredId ->
                File(appContext.filesDir, "offline-models/$retiredId").deleteRecursively()
            }
    }

    fun supports(source: AppLanguage, target: AppLanguage): Boolean =
        manifest.supports(source, target)

    suspend fun downloadAndInstall() = withContext(Dispatchers.IO) {
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
                    throw IOException("Model download returned HTTP ${response.code}.")
                }
                val body = response.body ?: throw IOException("Model download returned no data.")
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

            if (!OfflineModelIntegrity.matchesChecksum(download, manifest.sha256)) {
                download.delete()
                _status.value = OfflineModelStatus.Invalid("Downloaded file checksum mismatch.")
                return@withContext
            }

            staging.mkdirs()
            unpackSafely(download, staging)
            val validationError = OfflineModelIntegrity.validate(staging, manifest)
            if (validationError != null) {
                staging.deleteRecursively()
                download.delete()
                _status.value = OfflineModelStatus.Invalid(validationError)
                return@withContext
            }

            installDirectory.deleteRecursively()
            if (!staging.renameTo(installDirectory)) {
                throw IOException("Could not activate downloaded model.")
            }
            download.delete()
            _status.value = OfflineModelStatus.Installed(manifest.installedSizeLabel)
        } catch (error: Exception) {
            download.delete()
            staging.deleteRecursively()
            _status.value = OfflineModelStatus.Error(error.message ?: "Model installation failed.")
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        modelRoot.deleteRecursively()
        _status.value = OfflineModelStatus.NotInstalled
    }

    fun installedFiles(): OfflineModelFiles {
        val validationError = OfflineModelIntegrity.validate(installDirectory, manifest)
        if (validationError != null) {
            _status.value = if (installDirectory.exists()) {
                OfflineModelStatus.Invalid(validationError)
            } else {
                OfflineModelStatus.NotInstalled
            }
            error(validationError)
        }
        return OfflineModelFiles(
            model = File(installDirectory, manifest.modelFile),
            sourceVocab = File(installDirectory, "source.spm"),
            targetVocab = File(installDirectory, "target.spm"),
            shortlist = File(installDirectory, "lex.s2t.bin"),
            config = File(installDirectory, "config.yml"),
        )
    }

    private fun inspectInstallation(): OfflineModelStatus {
        if (!installDirectory.exists()) return OfflineModelStatus.NotInstalled
        val error = OfflineModelIntegrity.validate(installDirectory, manifest)
        return if (error == null) {
            OfflineModelStatus.Installed(manifest.installedSizeLabel)
        } else {
            OfflineModelStatus.Invalid(error)
        }
    }

    private fun unpackSafely(zip: File, destination: File) {
        val destinationPrefix = destination.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zip).buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val output = File(destination, entry.name)
                if (!output.canonicalPath.startsWith(destinationPrefix)) {
                    throw IOException("Model archive contains an unsafe path.")
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).buffered().use { fileOutput ->
                        input.copyTo(fileOutput)
                    }
                }
                input.closeEntry()
            }
        }
    }

}
