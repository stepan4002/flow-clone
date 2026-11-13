package com.flowkey.ime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePolicyTest {

    private fun sec(s: Double) = (s * 16000).toLong()

    @Test fun `partials wait at least 0_7 s of audio`() {
        assertFalse(LivePolicy.partialDue(sec(0.6), lastDecodeMs = 0))
        assertTrue(LivePolicy.partialDue(sec(0.7), lastDecodeMs = 0))
    }

    @Test fun `slow decodes space partials out`() {
        // Last partial took 2 s -> wait for 3 s of new audio.
        assertFalse(LivePolicy.partialDue(sec(2.9), lastDecodeMs = 2000))
        assertTrue(LivePolicy.partialDue(sec(3.0), lastDecodeMs = 2000))
    }

    @Test fun `auto-stop counts audio, not wall-clock time`() {
        assertFalse(LivePolicy.autoStop(heardVoice = true, samplesSinceVoice = sec(2.4), autoStopMs = 2500))
        assertTrue(LivePolicy.autoStop(heardVoice = true, samplesSinceVoice = sec(2.5), autoStopMs = 2500))
        assertFalse(LivePolicy.autoStop(heardVoice = false, samplesSinceVoice = sec(60.0), autoStopMs = 2500))
    }

    @Test fun `no speech and max take`() {
        assertTrue(LivePolicy.noSpeech(heardVoice = false, samplesTotal = sec(8.0)))
        assertFalse(LivePolicy.noSpeech(heardVoice = true, samplesTotal = sec(8.0)))
        assertFalse(LivePolicy.maxTake(sec(299.0)))
        assertTrue(LivePolicy.maxTake(sec(300.0)))
    }
}

class WhisperChunksTest {

    @Test fun `split keeps every sample in chunks of at most 28 s`() {
        val max = WhisperChunks.MAX_SAMPLES
        assertEquals(0, WhisperChunks.split(FloatArray(0)).size)
        assertEquals(1, WhisperChunks.split(FloatArray(16000 * 27)).size)
        assertEquals(1, WhisperChunks.split(FloatArray(max)).size)
        val sixtyOne = FloatArray(16000 * 61) { it.toFloat() }
        val chunks = WhisperChunks.split(sixtyOne)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.size <= max })
        assertArrayEquals(sixtyOne, WhisperChunks.concat(chunks), 0f)
    }

    @Test fun `concat joins recorded chunks in order`() {
        val joined = WhisperChunks.concat(listOf(floatArrayOf(1f, 2f), floatArrayOf(), floatArrayOf(3f)))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), joined, 0f)
    }
}
