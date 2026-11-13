package com.flowkey.ime

/**
 * The most recent audio of a live session, addressed by absolute sample index (the same
 * index the VAD reports for its segments). Old audio beyond [capacity] samples is dropped.
 */
class AudioHistory(private val capacity: Long) {

    private val chunks = ArrayDeque<FloatArray>()

    /** Absolute index of the first sample still kept. */
    var start = 0L
        private set

    /** Absolute index one past the last sample added (= total samples added). */
    var end = 0L
        private set

    fun add(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        chunks.addLast(chunk)
        end += chunk.size
        while (chunks.size > 1 && end - (start + chunks.first().size) >= capacity) {
            start += chunks.removeFirst().size
        }
    }

    /** Samples in [from, to), clamped to what is kept. */
    fun slice(from: Long, to: Long): FloatArray {
        val lo = from.coerceIn(start, end)
        val hi = to.coerceIn(lo, end)
        val out = FloatArray((hi - lo).toInt())
        var pos = start
        var written = 0
        for (c in chunks) {
            val cEnd = pos + c.size
            if (cEnd > lo && pos < hi) {
                val a = (maxOf(lo, pos) - pos).toInt()
                val b = (minOf(hi, cEnd) - pos).toInt()
                c.copyInto(out, written, a, b)
                written += b - a
            }
            pos = cEnd
            if (pos >= hi) break
        }
        return out
    }
}
