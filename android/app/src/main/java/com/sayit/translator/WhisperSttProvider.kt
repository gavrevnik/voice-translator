package com.sayit.translator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperSttProvider(private val modelManager: WhisperModelManager) : SttProvider {
    private val audioRecorder = WhisperPcmAudioRecorder()
    private var language = AppLanguage.ENGLISH
    private var partialResult: (String) -> Unit = {}
    private var whisperContext: WhisperContext? = null

    override suspend fun start(language: AppLanguage, onPartialResult: (String) -> Unit) {
        this.language = language
        partialResult = onPartialResult
        audioRecorder.start()
    }

    override suspend fun stop(): String = withContext(Dispatchers.Default) {
        val samples = audioRecorder.stop()
        if (samples.isEmpty()) error("No audio was recorded.")
        val engine = whisperContext ?: WhisperContext.createContextFromFile(
            modelManager.installedModel().absolutePath,
        ).also { whisperContext = it }
        engine.transcribeData(samples, language.whisperCode).trim().ifBlank {
            error("Whisper could not recognize speech. Try speaking closer to the phone.")
        }.also(partialResult)
    }

    override fun cancel() {
        audioRecorder.cancel()
        partialResult = {}
    }

    suspend fun release() {
        whisperContext?.release()
        whisperContext = null
    }
}

private class WhisperPcmAudioRecorder {
    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var failure: Throwable? = null
    private var output = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start() {
        check(!recording) { "Recording is already active." }
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "16 kHz microphone recording is unavailable." }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Could not initialize the microphone."
        }

        output = ByteArrayOutputStream()
        failure = null
        audioRecord = recorder
        recording = true
        recorder.startRecording()
        worker = Thread({ captureLoop(recorder, minBufferSize) }, "SayItWhisperRecorder").apply {
            start()
        }
    }

    fun stop(): FloatArray {
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        worker = null
        audioRecord?.release()
        audioRecord = null
        failure?.let { throw it }

        val bytes = output.toByteArray()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get() / 32768f }
    }

    fun cancel() {
        recording = false
        runCatching { audioRecord?.stop() }
        worker?.join(500)
        worker = null
        audioRecord?.release()
        audioRecord = null
        output.reset()
    }

    private fun captureLoop(recorder: AudioRecord, minBufferSize: Int) {
        val samples = ShortArray(minBufferSize.coerceAtLeast(2) / 2)
        val bytes = ByteArray(samples.size * 2)
        try {
            while (recording) {
                val count = recorder.read(samples, 0, samples.size)
                if (count < 0) error("Microphone read failed with code $count.")
                var byteIndex = 0
                for (index in 0 until count) {
                    val sample = samples[index].toInt()
                    bytes[byteIndex++] = (sample and 0xff).toByte()
                    bytes[byteIndex++] = ((sample shr 8) and 0xff).toByte()
                }
                output.write(bytes, 0, count * 2)
            }
        } catch (throwable: Throwable) {
            if (recording) failure = throwable
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
