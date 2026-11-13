package com.flowkey.ime

import java.text.Normalizer

/**
 * Spoken punctuation and editing commands, per language.
 *
 * Matching works on normalized tokens (lower case, accents folded, punctuation that Whisper
 * attached stripped), so "Nový řádek." and "novy radek" both match "nový řádek".
 *
 * Words that are also ordinary words ("point", "punto", "Punkt", ...) are marked [Command.endOnly]:
 * they only count as a command at the end of the utterance or right before another command,
 * so "à ce point" or "en punto" stay untouched.
 */
internal object PunctuationCommands {

    internal sealed interface Piece
    internal data class Word(val text: String) : Piece
    internal data class Punct(val symbol: String) : Piece
    internal data class Break(val text: String) : Piece
    internal data object OpenQuote : Piece
    internal data object CloseQuote : Piece
    /** "scratch that": drop everything dictated so far in this utterance. */
    internal data object Scratch : Piece

    internal data class Command(
        val words: List<String>,
        val pieces: List<Piece>,
        val endOnly: Boolean = false,
    )

    /** Languages that have a command table. */
    val SUPPORTED: List<String> = listOf("en", "cs", "sk", "fr", "es", "de", "it", "pt", "pl", "nl")

    private val TABLES: Map<String, List<Command>> = mapOf(
        "en" to table {
            cmd(".", "period", "full stop")
            cmd(",", "comma")
            cmd("?", "question mark")
            cmd("!", "exclamation mark", "exclamation point")
            cmd(":", "colon")
            cmd(";", "semicolon")
            cmd("-", "dash", "hyphen")
            cmd("—", "em dash")
            cmd("…", "ellipsis")
            brk("\n", "new line")
            brk("\n\n", "new paragraph")
            open("open quote")
            close("close quote")
            scratch("scratch that", "delete that", "forget that")
        },
        "cs" to table {
            cmd(".", "tečka")
            cmd(",", "čárka")
            cmd("?", "otazník")
            cmd("!", "vykřičník")
            cmd(":", "dvojtečka")
            cmd(";", "středník")
            cmd("-", "pomlčka")
            cmd("…", "tři tečky")
            brk("\n", "nový řádek")
            brk("\n\n", "nový odstavec")
            open("otevřít uvozovky", "uvozovky začátek")
            close("zavřít uvozovky", "uvozovky konec")
            scratch("smaž to", "zruš to")
        },
        "sk" to table {
            cmd(".", "bodka")
            cmd(",", "čiarka")
            cmd("?", "otáznik")
            cmd("!", "výkričník")
            cmd(":", "dvojbodka")
            cmd(";", "bodkočiarka")
            cmd("-", "pomlčka")
            cmd("…", "tri bodky")
            brk("\n", "nový riadok")
            brk("\n\n", "nový odsek")
            open("otvoriť úvodzovky")
            close("zatvoriť úvodzovky")
            scratch("zmaž to")
        },
        "fr" to table {
            cmd(".", "point final")
            cmdEnd(".", "point")
            cmd(",", "virgule")
            cmd(";", "point virgule", "point-virgule")
            cmd("?", "point d'interrogation")
            cmd("!", "point d'exclamation")
            cmd(":", "deux points", "deux-points")
            cmd("-", "tiret")
            cmd("…", "points de suspension")
            brk("\n", "à la ligne", "nouvelle ligne")
            brk("\n\n", "nouveau paragraphe")
            open("ouvrir les guillemets", "ouvrez les guillemets")
            close("fermer les guillemets", "fermez les guillemets")
            scratch("efface ça")
        },
        "es" to table {
            cmd(".", "punto final", "punto y seguido")
            cmdEnd(".", "punto")
            add(listOf(Punct("."), Break("\n")), "punto y aparte")
            cmd(",", "coma")
            cmd(";", "punto y coma")
            cmd(":", "dos puntos")
            cmd("?", "signo de interrogación")
            cmd("!", "signo de exclamación")
            cmd("-", "guion")
            cmd("…", "puntos suspensivos")
            brk("\n", "nueva línea")
            brk("\n\n", "nuevo párrafo")
            open("abrir comillas")
            close("cerrar comillas")
            scratch("borra eso")
        },
        "de" to table {
            cmdEnd(".", "punkt")
            cmd(",", "komma")
            cmd("?", "fragezeichen")
            cmd("!", "ausrufezeichen")
            cmd(":", "doppelpunkt")
            cmd(";", "semikolon")
            cmd("-", "bindestrich")
            cmd("—", "gedankenstrich")
            brk("\n", "neue zeile")
            brk("\n\n", "neuer absatz")
            open("anführungszeichen auf", "anführungszeichen unten")
            close("anführungszeichen zu", "anführungszeichen oben")
            scratch("lösch das", "lösche das")
        },
        "it" to table {
            cmdEnd(".", "punto")
            cmd(",", "virgola")
            cmd("?", "punto interrogativo")
            cmd("!", "punto esclamativo")
            cmd(":", "due punti")
            cmd(";", "punto e virgola")
            cmd("-", "trattino")
            brk("\n", "nuova riga", "a capo")
            brk("\n\n", "nuovo paragrafo")
            open("apri virgolette")
            close("chiudi virgolette")
            scratch("cancella tutto")
        },
        "pt" to table {
            cmdEnd(".", "ponto")
            cmd(".", "ponto final")
            cmd(",", "vírgula")
            cmd("?", "ponto de interrogação")
            cmd("!", "ponto de exclamação")
            cmd(":", "dois pontos")
            cmd(";", "ponto e vírgula")
            cmd("-", "hífen")
            brk("\n", "nova linha")
            brk("\n\n", "novo parágrafo")
            open("abre aspas", "abrir aspas")
            close("fecha aspas", "fechar aspas")
            scratch("apaga isso")
        },
        "pl" to table {
            cmd(".", "kropka")
            cmd(",", "przecinek")
            cmd("?", "znak zapytania")
            cmd("!", "wykrzyknik")
            cmd(":", "dwukropek")
            cmd(";", "średnik")
            cmd("-", "myślnik")
            brk("\n", "nowa linia")
            brk("\n\n", "nowy akapit")
            open("otwórz cudzysłów")
            close("zamknij cudzysłów")
            scratch("usuń to")
        },
        "nl" to table {
            cmdEnd(".", "punt")
            cmd(",", "komma")
            cmd("?", "vraagteken")
            cmd("!", "uitroepteken")
            cmd(":", "dubbele punt")
            cmd(";", "puntkomma")
            cmd("-", "streepje")
            brk("\n", "nieuwe regel")
            brk("\n\n", "nieuwe alinea")
            open("aanhalingstekens openen")
            close("aanhalingstekens sluiten")
            scratch("wis dat")
        },
    )

    /** Longest command, in tokens, across all tables. */
    val MAX_WORDS: Int = TABLES.values.flatten().maxOf { it.words.size }

    /**
     * The command table for an utterance. `null` (language unknown) means English, the
     * historical behaviour. A language without a table gets no commands: English command
     * words are never applied to, say, Czech text.
     */
    fun tableFor(lang: String?): List<Command> =
        if (lang == null) TABLES.getValue("en") else TABLES[lang].orEmpty()

    /** Lower case, typographic apostrophe -> ', accents folded, edge punctuation trimmed. */
    fun normalize(token: String): String {
        val folded = Normalizer.normalize(token.lowercase().replace('’', '\''), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return folded.trim { !it.isLetterOrDigit() && it != '\'' }
    }

    // ------------------------------------------------------------ table DSL

    private class TableBuilder {
        val commands = mutableListOf<Command>()

        fun add(pieces: List<Piece>, vararg phrases: String, endOnly: Boolean = false) {
            for (phrase in phrases) {
                val words = phrase.split(' ').map { normalize(it) }.filter { it.isNotEmpty() }
                commands += Command(words, pieces, endOnly)
            }
        }

        fun cmd(symbol: String, vararg phrases: String) = add(listOf(Punct(symbol)), *phrases)
        fun cmdEnd(symbol: String, vararg phrases: String) =
            add(listOf(Punct(symbol)), *phrases, endOnly = true)
        fun brk(text: String, vararg phrases: String) = add(listOf(Break(text)), *phrases)
        fun open(vararg phrases: String) = add(listOf(OpenQuote), *phrases)
        fun close(vararg phrases: String) = add(listOf(CloseQuote), *phrases)
        fun scratch(vararg phrases: String) = add(listOf(Scratch), *phrases)
    }

    private fun table(block: TableBuilder.() -> Unit): List<Command> =
        TableBuilder().apply(block).commands
            // Longest first, so "point virgule" wins over "point".
            .sortedByDescending { it.words.size }
}
