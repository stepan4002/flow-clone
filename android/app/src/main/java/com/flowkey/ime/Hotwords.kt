package com.flowkey.ime

import java.util.Locale

/**
 * Turns the user's terms into a sherpa-onnx hotwords file for the English streaming Zipformer.
 *
 * Verified constraints of that model (sherpa-onnx-streaming-zipformer-en-2023-06-26):
 *  - its BPE pieces are UPPER CASE, so lower-case hotwords silently do nothing;
 *  - each line is "PHRASE :score"; ~4 biases recognition, ~20 corrupts words;
 *  - only A-Z and the apostrophe are in its vocabulary, so anything else is dropped here
 *    rather than handed to native code.
 */
object Hotwords {

    const val SCORE = 4.0f
    private const val MAX_TOKENS = 6
    private val VALID = Regex("^[A-Z']+( [A-Z']+){0,${MAX_TOKENS - 1}}$")
    private val WHITESPACE = Regex("\\s+")

    /** Hotword file lines for [terms]; empty when none are usable. */
    fun format(terms: List<String>, score: Float = SCORE): List<String> =
        terms.asSequence()
            .map { it.replace('’', '\'').trim().replace(WHITESPACE, " ").uppercase(Locale.ROOT) }
            .filter { VALID.matches(it) }
            .distinct()
            .map { "$it :${"%.1f".format(Locale.ROOT, score)}" }
            .toList()
}
