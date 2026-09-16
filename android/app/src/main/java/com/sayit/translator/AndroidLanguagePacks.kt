package com.sayit.translator

enum class AndroidLanguagePackAvailability {
    CHECKING,
    INSTALLED,
    DOWNLOADABLE,
    SETUP_REQUIRED,
    DOWNLOADING,
    SCHEDULED,
    ONLINE_ONLY,
    UNSUPPORTED,
    UNKNOWN,
    ERROR,
}

data class AndroidLanguagePackStatus(
    val availability: AndroidLanguagePackAvailability,
    val providerName: String = "",
    val progressPercent: Int? = null,
    val detail: String = "",
) {
    val isInstalled: Boolean
        get() = availability == AndroidLanguagePackAvailability.INSTALLED

    val canDownload: Boolean
        get() = availability == AndroidLanguagePackAvailability.DOWNLOADABLE

    val isListedPackage: Boolean
        get() = availability in setOf(
            AndroidLanguagePackAvailability.CHECKING,
            AndroidLanguagePackAvailability.INSTALLED,
            AndroidLanguagePackAvailability.DOWNLOADABLE,
            AndroidLanguagePackAvailability.SETUP_REQUIRED,
            AndroidLanguagePackAvailability.DOWNLOADING,
            AndroidLanguagePackAvailability.SCHEDULED,
        )

    companion object {
        val CHECKING = AndroidLanguagePackStatus(AndroidLanguagePackAvailability.CHECKING)
    }
}

internal enum class AndroidSpeechVendor {
    SAMSUNG,
    GOOGLE,
    PIPER,
    OTHER,
}

internal fun androidSpeechVendor(packageName: String): AndroidSpeechVendor = when {
    packageName.startsWith("com.samsung.", ignoreCase = true) -> AndroidSpeechVendor.SAMSUNG
    packageName.startsWith("com.google.", ignoreCase = true) -> AndroidSpeechVendor.GOOGLE
    isPiperTtsProvider(packageName) -> AndroidSpeechVendor.PIPER
    else -> AndroidSpeechVendor.OTHER
}

internal fun androidSpeechProviderPriority(packageName: String): Int = when (
    androidSpeechVendor(packageName)
) {
    AndroidSpeechVendor.SAMSUNG -> 0
    AndroidSpeechVendor.GOOGLE -> 1
    AndroidSpeechVendor.PIPER -> 2
    AndroidSpeechVendor.OTHER -> 3
}

internal fun isSamsungOrGoogleSpeechProvider(packageName: String): Boolean =
    packageName.startsWith("com.samsung.", ignoreCase = true) ||
        packageName.startsWith("com.google.", ignoreCase = true)

internal fun isPiperTtsProvider(packageName: String): Boolean =
    packageName == PIPER_TTS_PACKAGE_NAME

internal fun isSupportedAndroidTtsProvider(packageName: String): Boolean =
    isSamsungOrGoogleSpeechProvider(packageName) || isPiperTtsProvider(packageName)

internal fun androidSpeechProviderName(packageName: String, serviceLabel: String): String = when (
    androidSpeechVendor(packageName)
) {
    AndroidSpeechVendor.SAMSUNG -> "Samsung"
    AndroidSpeechVendor.GOOGLE -> "Google"
    AndroidSpeechVendor.PIPER -> PIPER_TTS_PROVIDER_NAME
    AndroidSpeechVendor.OTHER -> serviceLabel.ifBlank { packageName }
}

internal const val PIPER_TTS_PACKAGE_NAME = "com.k2fsa.sherpa.onnx.tts.engine"
internal const val PIPER_TTS_PROVIDER_NAME = "Piper Serbian (ONNX)"

private const val PIPER_TTS_APK_BASE_URL =
    "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/" +
        "tts-engine-new/1.13.4"
private const val PIPER_TTS_APK_FILE_PREFIX = "sherpa-onnx-1.13.4"
private const val PIPER_TTS_APK_FILE_SUFFIX =
    "srp-tts-engine-vits-piper-sr_RS-serbski_institut-medium.apk"
private val PIPER_TTS_SUPPORTED_ABIS = setOf(
    "arm64-v8a",
    "armeabi-v7a",
    "x86_64",
    "x86",
)

internal fun piperTtsDownloadUrl(supportedAbis: List<String>): String {
    val abi = supportedAbis.firstOrNull(PIPER_TTS_SUPPORTED_ABIS::contains) ?: "arm64-v8a"
    return "$PIPER_TTS_APK_BASE_URL/$PIPER_TTS_APK_FILE_PREFIX-$abi-$PIPER_TTS_APK_FILE_SUFFIX"
}
