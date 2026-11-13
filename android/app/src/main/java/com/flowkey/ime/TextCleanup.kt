package com.flowkey.ime

/**
 * Rule-based post-processing of a finalized utterance:
 * fillers, spoken punctuation commands (English), "scratch that",
 * user dictionary replacements, snippets, capitalization and spacing.
 */
object TextCleanup {

    // Conservative standalone fillers for EN / CS / FR / ES.
    private val FILLERS = setOf(
        "um", "umm", "uh", "uhm", "er", "erm", "hmm", "hm", "mhm", // en
        "ehm", "eh",                                               // cs (and general)
        "euh",                                                     // fr
        "este", "pues",                                            // es
    )

    // Internal markers so quotes can get asymmetric spacing during assembly.
    private const val OPEN_QUOTE_MARK = "<<OPEN_QUOTE>>"
    private const val CLOSE_QUOTE_MARK = "<<CLOSE_QUOTE>>"

    // Spoken punctuation commands are English-only for now.
    private val TWO_WORD_COMMANDS = mapOf(
        "full stop" to ".",
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "new line" to "\n",
        "new paragraph" to "\n\n",
        "open quote" to OPEN_QUOTE_MARK,
        "close quote" to CLOSE_QUOTE_MARK,
    )

    private val ONE_WORD_COMMANDS = mapOf(
        "period" to ".",
        "comma" to ",",
        "colon" to ":",
        "semicolon" to ";",
        "dash" to "-",
        "hyphen" to "-",
    )

    private sealed interface Piece
    private data class Word(val text: String) : Piece
    private data class Punct(val symbol: String) : Piece
    private data class Break(val text: String) : Piece
    private data object OpenQuote : Piece
    private data object CloseQuote : Piece

    fun process(
        raw: String,
        capitalizeStart: Boolean,
        dictionary: List<UserData.Entry>,
        snippets: List<UserData.Entry>,
        removeFillers: Boolean = true,
        punctuationCommands: Boolean = true,
        capitalizeEnglishI: Boolean = true,
    ): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        for ((from, to) in snippets) text = replacePhrase(text, from, to)
        for ((from, to) in dictionary) text = replacePhrase(text, from, to)

        val pieces = parse(text, removeFillers, punctuationCommands)
        var out = assemble(pieces)

        out = out.replace(Regex(" {2,}"), " ")
        out = out.replace(Regex(" +\n"), "\n").replace(Regex("\n +"), "\n")
        out = out.replace(Regex(" +([.,!?;:])"), "$1")
        out = capitalizeSentences(out, capitalizeStart)
        if (capitalizeEnglishI) {
            // covers i, i'm, i'll, i've, i'd — English only ("i" is a word in Czech)
            out = out.replace(Regex("\\bi\\b"), "I")
        }
        return out.trim(' ')
    }

    private fun parse(
        text: String,
        removeFillers: Boolean,
        punctuationCommands: Boolean,
    ): List<Piece> {
        val tokens = text.split(Regex("\\s+"))
        val pieces = mutableListOf<Piece>()
        var i = 0
        while (i < tokens.size) {
            // Whisper attaches punctuation to words; strip it for command matching only.
            val bare = tokens[i].lowercase().trim { !it.isLetterOrDigit() && it != '\'' }
            val two = if (i + 1 < tokens.size) {
                bare + " " + tokens[i + 1].lowercase().trim { !it.isLetterOrDigit() && it != '\'' }
            } else null

            if (two == "scratch that") {
                pieces.clear()
                i += 2
                continue
            }
            val twoSymbol = if (punctuationCommands) two?.let { TWO_WORD_COMMANDS[it] } else null
            if (twoSymbol != null) {
                pieces += pieceFor(twoSymbol)
                i += 2
                continue
            }
            val oneSymbol = if (punctuationCommands) ONE_WORD_COMMANDS[bare] else null
            if (oneSymbol != null) {
                pieces += pieceFor(oneSymbol)
                i++
                continue
            }
            if (removeFillers && bare in FILLERS) {
                i++
                continue
            }
            if (tokens[i].isNotEmpty()) pieces += Word(tokens[i])
            i++
        }
        return pieces
    }

    private fun pieceFor(symbol: String): Piece = when (symbol) {
        "\n", "\n\n" -> Break(symbol)
        OPEN_QUOTE_MARK -> OpenQuote
        CLOSE_QUOTE_MARK -> CloseQuote
        else -> Punct(symbol)
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
                    if (piece.symbol == "-") {
                        if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                        sb.append('-')
                    } else {
                        sb.append(piece.symbol)
                    }
                    pendingSpace = true
                }
                is Break -> {
                    sb.append(piece.text)
                    pendingSpace = false
                }
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
