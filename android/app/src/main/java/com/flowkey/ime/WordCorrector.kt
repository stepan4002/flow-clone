package com.flowkey.ime

import java.text.Normalizer

/**
 * Applies the user's own words ("your words" + dictionary targets) to recognized text:
 *
 *  - exact matches (ignoring case and accents) get the user's spelling: "github" -> "GitHub";
 *  - optionally, close misspellings of longer terms are corrected: "Kubernetis" -> "Kubernetes".
 *
 * What it must NOT do: force capitals onto ordinary words ("it" is not "IT", "may" is not
 * "May"), or "fix" grammatical forms ("Microsoftu", "pipelines", "Kubernetes's").
 *
 * Whisper in sherpa-onnx cannot be prompted with a vocabulary, so this post-correction is the
 * Whisper side of "better recognition of your own words". It is deliberately conservative:
 * only terms of [FUZZY_MIN_LENGTH]+ letters are fuzzy-matched, the first letter must agree,
 * and at most 1 edit (2 for terms of [FUZZY_TWO_EDITS_LENGTH]+ letters) is allowed.
 */
internal object WordCorrector {

    /**
     * Languages where close-misspelling fixes are safe. Languages with case endings (Czech,
     * Slovak, Polish, ...) change the end of names and nouns all the time ("Kateřino",
     * "Kateřinu"), which a 1-2 edit match cannot tell apart from a misspelling.
     */
    val FUZZY_LANGUAGES = setOf("en", "fr", "es", "it", "pt", "nl", "de")

    const val FUZZY_MIN_LENGTH = 6
    const val FUZZY_TWO_EDITS_LENGTH = 9
    private const val MAX_TERM_TOKENS = 6

    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")

    /**
     * @param caseOnly whether a match that differs ONLY in letter case may be rewritten. Off
     *   for short all-caps terms ("IT", "US") and single capitalized words ("May", "Mark"),
     *   which collide with ordinary words. Accent fixes ("dvorak" -> "Dvořák") still apply.
     */
    private class Term(val text: String, val norm: List<String>, val caseOnly: Boolean) {
        val joined = norm.joinToString("")
    }

    private data class Token(val start: Int, val end: Int, val norm: String)

    fun apply(text: String, terms: List<String>, fuzzy: Boolean): String {
        if (text.isEmpty() || terms.isEmpty()) return text
        val prepared = terms.mapNotNull { term ->
            val parts = TOKEN.findAll(term).map { it.value }.toList()
            // Only plain words: "C++" or "a.b" would be replaced over just part of the match.
            val plain = parts.joinToString(" ") == term.trim().split(WHITESPACE).joinToString(" ")
            if (!plain || parts.isEmpty() || parts.size > MAX_TERM_TOKENS) {
                null
            } else {
                val letters = parts.sumOf { p -> p.count { it.isLetterOrDigit() } }
                val allCaps = parts.all { p -> p == p.uppercase() }
                val capitalizedWord = parts.size == 1 && parts[0].first().isUpperCase() &&
                    parts[0].drop(1) == parts[0].drop(1).lowercase()
                val caseOnly = letters > 2 && !(allCaps && letters <= 3) && !capitalizedWord
                Term(parts.joinToString(" "), parts.map { normalize(it) }, caseOnly)
            }
        }
            // Longer terms first, so "Flow Clone" wins over "Flow".
            .sortedWith(compareByDescending<Term> { it.norm.size }.thenByDescending { it.joined.length })
        if (prepared.isEmpty()) return text

        val tokens = TOKEN.findAll(text).map { Token(it.range.first, it.range.last + 1, normalize(it.value)) }.toList()
        val out = StringBuilder(text.length)
        var copiedUpTo = 0
        var i = 0
        while (i < tokens.size) {
            val exact = prepared.firstOrNull { exactAt(text, tokens, i, it) }
            val hit = exact ?: if (fuzzy) prepared.firstOrNull { fuzzyAt(text, tokens, i, it) } else null
            if (hit == null) {
                i++
                continue
            }
            val first = tokens[i]
            val last = tokens[i + hit.norm.size - 1]
            val found = text.substring(first.start, last.end)
            val caseOnlyDiff = found.split(WHITESPACE).joinToString(" ").equals(hit.text, ignoreCase = true)
            if (exact != null && caseOnlyDiff && !hit.caseOnly) {
                i += hit.norm.size // an ordinary word that merely looks like the term: keep it
                continue
            }
            out.append(text, copiedUpTo, first.start).append(hit.text)
            copiedUpTo = last.end
            i += hit.norm.size
        }
        out.append(text, copiedUpTo, text.length)
        return out.toString()
    }

    /** Tokens i until i+k, if they exist and are separated by whitespace only. */
    private fun window(text: String, tokens: List<Token>, i: Int, k: Int): List<Token>? {
        if (i + k > tokens.size) return null
        val w = tokens.subList(i, i + k)
        for (j in 1 until w.size) {
            val gap = text.substring(w[j - 1].end, w[j].start)
            if (gap.isNotBlank() || gap.contains('\n')) return null // never merge across lines
        }
        return w
    }

    private fun exactAt(text: String, tokens: List<Token>, i: Int, term: Term): Boolean {
        val w = window(text, tokens, i, term.norm.size) ?: return false
        return w.map { it.norm } == term.norm
    }

    private fun fuzzyAt(text: String, tokens: List<Token>, i: Int, term: Term): Boolean {
        if (term.joined.length < FUZZY_MIN_LENGTH) return false
        val w = window(text, tokens, i, term.norm.size) ?: return false
        val candidate = w.joinToString("") { it.norm }
        if (candidate.firstOrNull() != term.joined.first()) return false
        // The term plus an ending is a grammatical form, not a misspelling ("pipelines").
        if (candidate.startsWith(term.joined)) return false
        val maxEdits = if (term.joined.length >= FUZZY_TWO_EDITS_LENGTH) 2 else 1
        return levenshtein(candidate, term.joined, maxEdits) <= maxEdits
    }

    private fun normalize(token: String): String =
        Normalizer.normalize(token.lowercase().replace('’', '\''), Normalizer.Form.NFD)
            .replace(MARKS, "")

    /** Edit distance, giving up early (returning limit + 1) once it exceeds [limit]. */
    internal fun levenshtein(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (x in 1..a.length) {
            cur[0] = x
            var rowMin = cur[0]
            for (y in 1..b.length) {
                val cost = if (a[x - 1] == b[y - 1]) 0 else 1
                cur[y] = minOf(prev[y] + 1, cur[y - 1] + 1, prev[y - 1] + cost)
                if (cur[y] < rowMin) rowMin = cur[y]
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
