package com.flowkey.ime

import com.flowkey.ime.PunctuationCommands.Break
import com.flowkey.ime.PunctuationCommands.CloseQuote
import com.flowkey.ime.PunctuationCommands.Command
import com.flowkey.ime.PunctuationCommands.OpenQuote
import com.flowkey.ime.PunctuationCommands.Piece
import com.flowkey.ime.PunctuationCommands.Punct
import com.flowkey.ime.PunctuationCommands.Scratch
import com.flowkey.ime.PunctuationCommands.Word

/**
 * Rule-based post-processing of a finalized utterance:
 * fillers, spoken punctuation commands (per language, see [PunctuationCommands]),
 * "scratch that", user dictionary replacements, snippets, capitalization and spacing.
 */
object TextCleanup {

    /**
     * Standalone fillers. Only sounds that are not words in any supported language are
     * removed everywhere; "um" (Portuguese "a"), "er" (German "he"), "eh" and "este"
     * (Spanish "this") are words somewhere, so they are removed only in their language.
     */
    private val FILLERS_ANY = setOf("umm", "uhm", "uh", "hmm", "hm", "mhm", "ehm", "erm")
    private val FILLERS_BY_LANGUAGE = mapOf(
        "en" to setOf("um", "er"),
        "cs" to setOf("eh"),
        "sk" to setOf("eh"),
        "fr" to setOf("euh"),
    )

    private fun fillersFor(lang: String?): Set<String> =
        FILLERS_ANY + FILLERS_BY_LANGUAGE[lang ?: "en"].orEmpty()

    /**
     * Separates the phrases of a live session in the raw text. It counts as the end of an
     * utterance for end-only commands ("Punkt" at the end of a phrase) and is never output.
     */
    const val PHRASE_BREAK = "\u001F"

    /** Punctuation Whisper attaches to a word, removed when a spoken command follows. */
    private const val TRAILING_PUNCT = ".,!?;:…"

    /**
     * @param commandLanguage language whose spoken commands apply; null = unknown (English
     *   commands, no English-only "i" fix); a language without a table gets no commands.
     * @param words the user's terms; exact matches get the user's spelling.
     * @param correctWords also fix close misspellings of [words] (see [WordCorrector]).
     */
    fun process(
        raw: String,
        capitalizeStart: Boolean,
        dictionary: List<UserData.Entry>,
        snippets: List<UserData.Entry>,
        removeFillers: Boolean = true,
        punctuationCommands: Boolean = true,
        commandLanguage: String? = "en",
        words: List<String> = emptyList(),
        correctWords: Boolean = false,
    ): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        for ((from, to) in snippets) text = replacePhrase(text, from, to)
        for ((from, to) in dictionary) text = replacePhrase(text, from, to)

        val fillers = if (removeFillers) fillersFor(commandLanguage) else emptySet()
        val pieces = parse(text, fillers, commands(commandLanguage, punctuationCommands))
        var out = assemble(pieces)

        out = out.replace(Regex(" {2,}"), " ")
        out = out.replace(Regex(" +\n"), "\n").replace(Regex("\n +"), "\n")
        out = out.replace(Regex(" +([.,!?;:…])"), "$1")
        out = capitalizeSentences(out, capitalizeStart)
        if (commandLanguage == "en") {
            // covers i, i'm, i'll, i've, i'd — English only ("i" is a word in Czech)
            out = out.replace(Regex("\\bi\\b"), "I")
        }
        // Last, so the user's own spelling wins even at the start of a sentence ("iPhone").
        val fuzzy = correctWords && (commandLanguage ?: "en") in WordCorrector.FUZZY_LANGUAGES
        out = WordCorrector.apply(out, words, fuzzy)
        return out.trim(' ')
    }

    /** The active commands; with punctuation commands off, "scratch that" still works. */
    private fun commands(lang: String?, punctuationCommands: Boolean): List<Command> {
        val table = PunctuationCommands.tableFor(lang)
        return if (punctuationCommands) table else table.filter { it.pieces == listOf(Scratch) }
    }

    private fun parse(text: String, fillers: Set<String>, commands: List<Command>): List<Piece> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Whisper attaches punctuation to words; commands match on the normalized form only.
        val norm = tokens.map { PunctuationCommands.normalize(it) }

        fun matchAt(i: Int): Command? {
            for (c in commands) {
                val n = c.words.size
                if (i + n > tokens.size) continue
                if ((0 until n).any { norm[i + it] != c.words[it] }) continue
                // Ambiguous words only count at the end or directly before another command.
                if (!c.endOnly || i + n == tokens.size || tokens[i + n] == PHRASE_BREAK ||
                    matchAt(i + n) != null
                ) {
                    return c
                }
            }
            return null
        }

        val pieces = mutableListOf<Piece>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] == PHRASE_BREAK) {
                i++
                continue
            }
            val command = matchAt(i)
            if (command != null) {
                for (piece in command.pieces) {
                    when (piece) {
                        Scratch -> pieces.clear()
                        is Punct -> {
                            stripTrailingPunct(pieces)
                            pieces += piece
                        }
                        else -> pieces += piece
                    }
                }
                i += command.words.size
                continue
            }
            if (norm[i] in fillers) {
                i++
                continue
            }
            pieces += Word(tokens[i])
            i++
        }
        return pieces
    }

    /** "Hello," + spoken "comma" must give "Hello," not "Hello,,". */
    private fun stripTrailingPunct(pieces: MutableList<Piece>) {
        val last = pieces.lastOrNull() as? Word ?: return
        val stripped = last.text.trimEnd { it in TRAILING_PUNCT }
        if (stripped.length == last.text.length) return
        if (stripped.isEmpty()) pieces.removeAt(pieces.lastIndex) else pieces[pieces.lastIndex] = Word(stripped)
    }

    private fun assemble(pieces: List<Piece>): String {
        val sb = StringBuilder()
        var pendingSpace = false
        for (piece in pieces) {
            when (piece) {
                is Word -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    sb.append(piece.text)
                    pendingSpace = true
                }
                is Punct -> {
                    if (piece.symbol == "-" || piece.symbol == "—") {
                        if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                        sb.append(piece.symbol)
                    } else {
                        sb.append(piece.symbol)
                    }
                    pendingSpace = true
                }
                is Break -> {
                    sb.append(piece.text)
                    pendingSpace = false
                }
                Scratch -> Unit // consumed while parsing
                OpenQuote -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    sb.append('"')
                    pendingSpace = false
                }
                CloseQuote -> {
                    sb.append('"')
                    pendingSpace = true
                }
            }
        }
        return sb.toString()
    }

    private fun capitalizeSentences(text: String, capitalizeStart: Boolean): String {
        val sb = StringBuilder(text.length)
        var capitalize = capitalizeStart
        for (ch in text) {
            if (capitalize && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capitalize = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') capitalize = true
            }
        }
        return sb.toString()
    }

    /** Case-insensitive whole-phrase replacement; `to` is inserted literally. */
    private fun replacePhrase(text: String, from: String, to: String): String {
        val phrase = from.trim()
        if (phrase.isEmpty()) return text
        val pattern = Regex(
            "(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])"
        )
        return pattern.replace(text) { to }
    }
}
