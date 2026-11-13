package com.flowkey.ime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioHistoryTest {

    private fun chunk(from: Int, n: Int) = FloatArray(n) { (from + it).toFloat() }

    @Test fun `slices across chunk boundaries by absolute index`() {
        val h = AudioHistory(capacity = 1_000)
        h.add(chunk(0, 10)); h.add(chunk(10, 10)); h.add(chunk(20, 10))
        assertEquals(30, h.end)
        assertArrayEquals(chunk(7, 8), h.slice(7, 15), 0f)
        assertArrayEquals(chunk(0, 30), h.slice(-5, 99), 0f) // clamped
        assertEquals(0, h.slice(20, 10).size)
    }

    @Test fun `old audio is dropped but indices stay absolute`() {
        val h = AudioHistory(capacity = 25)
        repeat(10) { h.add(chunk(it * 10, 10)) }
        assertEquals(100, h.end)
        assertEquals(70, h.start)
        assertArrayEquals(chunk(75, 10), h.slice(75, 85), 0f)
        assertArrayEquals(chunk(70, 5), h.slice(0, 75), 0f) // before start: clamped
    }
}
