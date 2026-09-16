package com.sayit.translator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPauseDetectorTest {
    private val speechFrame = ShortArray(FRAME_SAMPLES) { 1_000 }
    private val silenceFrame = ShortArray(FRAME_SAMPLES)

    @Test
    fun `silence cannot stop recording before speech starts`() {
        val detector = detector()

        repeat(300) {
            assertFalse(detector.accept(silenceFrame, silenceFrame.size))
        }
    }

    @Test
    fun `configured silence stops once after speech`() {
        val detector = detector()

        feedSpeechStart(detector)
        repeat(199) {
            assertFalse(detector.accept(silenceFrame, silenceFrame.size))
        }
        assertTrue(detector.accept(silenceFrame, silenceFrame.size))
        assertEquals(32_000L, detector.trailingSilenceSamples)
        assertFalse(detector.accept(silenceFrame, silenceFrame.size))
    }

    @Test
    fun `speech after a short pause resets the silence timer`() {
        val detector = detector()

        feedSpeechStart(detector)
        repeat(150) {
            assertFalse(detector.accept(silenceFrame, silenceFrame.size))
        }
        assertFalse(detector.accept(speechFrame, speechFrame.size))
        repeat(199) {
            assertFalse(detector.accept(silenceFrame, silenceFrame.size))
        }
        assertTrue(detector.accept(silenceFrame, silenceFrame.size))
    }

    @Test
    fun `auto stop upload keeps only 300 milliseconds after speech`() {
        val fiveSecondsOfPcm16 = SAMPLE_RATE * 5 * 2

        val cutByteCount = autoStopPcmCutByteCount(
            capturedBytesAtDetection = fiveSecondsOfPcm16,
            trailingSilenceSamples = SAMPLE_RATE * 2L,
            sampleRate = SAMPLE_RATE,
            postRollMs = GROQ_AUTO_STOP_POST_ROLL_MS,
        )

        assertEquals(SAMPLE_RATE * 33 / 10 * 2, cutByteCount)
    }

    @Test
    fun `auto stop upload never pads a pause shorter than post roll`() {
        val capturedBytes = SAMPLE_RATE * 2

        assertEquals(
            capturedBytes,
            autoStopPcmCutByteCount(
                capturedBytesAtDetection = capturedBytes,
                trailingSilenceSamples = SAMPLE_RATE / 10L,
                sampleRate = SAMPLE_RATE,
                postRollMs = GROQ_AUTO_STOP_POST_ROLL_MS,
            ),
        )
    }

    private fun detector() = SpeechPauseDetector(
        sampleRate = SAMPLE_RATE,
        silenceDurationMs = 2_000,
    )

    private fun feedSpeechStart(detector: SpeechPauseDetector) {
        repeat(15) {
            assertFalse(detector.accept(speechFrame, speechFrame.size))
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 160
    }
}
