package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(@Volatile private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    @Volatile private var lastDetailedTimings = WhisperDetailedTimings.EMPTY

    suspend fun transcribeData(
        data: FloatArray,
        language: String,
        numThreads: Int = WhisperCpuConfig.preferredThreadCount,
        initialPrompt: String? = null,
        finalDecode: Boolean = true,
        printTimestamp: Boolean = false,
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        check(
            WhisperLib.fullTranscribe(
                ptr,
                numThreads,
                data,
                language,
                initialPrompt,
                finalDecode,
            ) == 0,
        ) { "Whisper failed to transcribe audio." }
        lastDetailedTimings = WhisperDetailedTimings.fromNative(
            WhisperLib.getDetailedTimings(ptr),
        )
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                if (printTimestamp) {
                    val textTimestamp = "[${toTimestamp(WhisperLib.getTextSegmentT0(ptr, i))} --> ${toTimestamp(WhisperLib.getTextSegmentT1(ptr, i))}]"
                    val textSegment = WhisperLib.getTextSegment(ptr, i)
                    append("$textTimestamp: $textSegment\n")
                } else {
                    append(WhisperLib.getTextSegment(ptr, i))
                }
            }
        }
    }

    fun detailedTimings(): WhisperDetailedTimings = lastDetailedTimings

    /**
     * Interrupts a native decode without waiting for the single-thread executor.
     * This must stay synchronous: queuing it on [scope] would put it behind the
     * very inference that needs to be stopped.
     */
    fun requestAbort() {
        val contextPtr = ptr
        if (contextPtr != 0L) WhisperLib.requestAbort(contextPtr)
    }

    suspend fun detectSpeechBounds(
        data: FloatArray,
        vadModelPath: String,
        numThreads: Int,
        threshold: Float,
        minSpeechDurationMs: Int,
        minSilenceDurationMs: Int,
    ): LongArray? = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        WhisperLib.detectSpeechBounds(
            ptr,
            data,
            vadModelPath,
            numThreads,
            threshold,
            minSpeechDurationMs,
            minSilenceDurationMs,
        ).takeIf { it.size == 2 && it[1] > it[0] }
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        runBlocking {
            release()
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("fphp")) {
                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                        loadV8fp16 = true
                    }
                }
            }

            if (loadVfpv4) {
                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            } else if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            language: String,
            initialPrompt: String?,
            finalDecode: Boolean,
        ): Int
        external fun requestAbort(contextPtr: Long)
        external fun getDetailedTimings(contextPtr: Long): LongArray
        external fun detectSpeechBounds(
            contextPtr: Long,
            audioData: FloatArray,
            vadModelPath: String,
            numThreads: Int,
            threshold: Float,
            minSpeechDurationMs: Int,
            minSilenceDurationMs: Int,
        ): LongArray
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

data class WhisperDetailedTimings(
    val melUs: Long,
    val sampleUs: Long,
    val encodeUs: Long,
    val decodeUs: Long,
    val batchdUs: Long,
    val promptUs: Long,
    val sampleRuns: Long,
    val encodeRuns: Long,
    val decodeRuns: Long,
    val batchdRuns: Long,
    val promptRuns: Long,
    val fallbackPromptRuns: Long,
    val fallbackHallucinationRuns: Long,
) {
    companion object {
        val EMPTY = WhisperDetailedTimings(
            melUs = 0,
            sampleUs = 0,
            encodeUs = 0,
            decodeUs = 0,
            batchdUs = 0,
            promptUs = 0,
            sampleRuns = 0,
            encodeRuns = 0,
            decodeRuns = 0,
            batchdRuns = 0,
            promptRuns = 0,
            fallbackPromptRuns = 0,
            fallbackHallucinationRuns = 0,
        )

        internal fun fromNative(values: LongArray): WhisperDetailedTimings {
            if (values.size != 13) return EMPTY
            return WhisperDetailedTimings(
                melUs = values[0],
                sampleUs = values[1],
                encodeUs = values[2],
                decodeUs = values[3],
                batchdUs = values[4],
                promptUs = values[5],
                sampleRuns = values[6],
                encodeRuns = values[7],
                decodeRuns = values[8],
                batchdRuns = values[9],
                promptRuns = values[10],
                fallbackPromptRuns = values[11],
                fallbackHallucinationRuns = values[12],
            )
        }
    }
}

//  500 -> 00:05.000
// 6000 -> 01:00.000
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use {
            it.readText()
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}
