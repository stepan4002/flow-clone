package com.flowkey.ime

import com.k2fsa.sherpa.onnx.OfflineRecognizer

/**
 * Whisper only sees the first ~29.5 s of any input and silently drops the rest, so every
 * decode goes through here: audio is cut into chunks of at most [MAX_SAMPLES] and the texts
 * are joined.
 */
object WhisperChunks {

    const val SAMPLE_RATE = RecognizerManager.SAMPLE_RATE
    const val MAX_SAMPLES = SAMPLE_RATE * 28

    data class Result(val text: String, val lang: String?)

    /** Splits [samples] into consecutive chunks of at most [max] samples (none for empty input). */
    fun split(samples: FloatArray, max: Int = MAX_SAMPLES): List<FloatArray> {
        require(max > 0)
        if (samples.isEmpty()) return emptyList()
        return (samples.indices step max).map { from ->
            samples.copyOfRange(from, minOf(from + max, samples.size))
        }
    }

    /** Concatenates audio chunks (as recorded) into one array. */
    fun concat(chunks: List<FloatArray>): FloatArray {
        val all = FloatArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) {
            c.copyInto(all, pos)
            pos += c.size
        }
        return all
    }

    /** Decodes [samples] of any length; `lang` is the first language Whisper reported. */
    fun decodeAll(recognizer: OfflineRecognizer, samples: FloatArray): Result {
        val texts = ArrayList<String>()
        var lang: String? = null
        for (chunk in split(samples)) {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(chunk, SAMPLE_RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val text = result.text.trim()
                if (text.isNotEmpty()) texts += text
                if (lang == null && result.lang.isNotBlank()) lang = result.lang
            } finally {
                stream.release()
            }
        }
        return Result(texts.joinToString(" "), lang)
    }
}
