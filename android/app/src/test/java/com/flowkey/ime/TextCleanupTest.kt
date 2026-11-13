package com.flowkey.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanupTest {

    private fun clean(
        raw: String,
        lang: String? = "en",
        capitalize: Boolean = true,
        punctuation: Boolean = true,
        dictionary: List<UserData.Entry> = emptyList(),
    ): String = TextCleanup.process(
        raw,
        capitalizeStart = capitalize,
        dictionary = dictionary,
        snippets = emptyList(),
        punctuationCommands = punctuation,
        commandLanguage = lang,
    )

    // Ported from windows/tests/test_cleanup.py (Android capitalizes after a new line).
    @Test fun `windows parity cases`() {
        assertEquals("So I think we should go", clean("um so I think uh we should go"))
        assertEquals("Hello, how are you?", clean("hello comma how are you question mark"))
        assertEquals("Send it today", clean("send it tomorrow scratch that send it today"))
        assertEquals("Bring an umbrella", clean("bring an umbrella"))
        assertEquals("Look at the periodic table", clean("look at the periodic table"))
        assertEquals("First line\nSecond line", clean("first line new line second line"))
        assertEquals("It works!", clean("it works exclamation mark"))
        assertEquals("Done. Next one", clean("done period next one"))
    }

    @Test fun `punctuation whisper already attached is not doubled`() {
        assertEquals("Hello, how are you?", clean("Hello, comma, how are you?"))
        assertEquals("Done.", clean("Done. Period."))
    }

    @Test fun `english extras`() {
        assertEquals("Wait — what", clean("wait em dash what"))
        assertEquals("Well…", clean("well ellipsis"))
        assertEquals("Fine", clean("nope delete that fine"))
        assertEquals("He said \"hi\"", clean("he said open quote hi close quote"))
    }

    @Test fun `czech`() {
        assertEquals("Ahoj, jak se máš?", clean("ahoj čárka jak se máš otazník", "cs"))
        assertEquals("Konec.\nDalší", clean("Konec tečka nový řádek další", "cs"))
        assertEquals("\n", clean("novy radek", "cs"))
        assertEquals("Pošli to dnes", clean("pošli to zítra smaž to pošli to dnes", "cs"))
        assertEquals("I tak", clean("i tak", "cs")) // Czech "i" is not the English pronoun
    }

    @Test fun `accent-folded and whisper-cased commands match`() {
        assertEquals("Ano.\nNe.", clean("Ano. Novy radek. Ne.", "cs"))
    }

    @Test fun `slovak`() {
        assertEquals("Ahoj, ako sa máš?", clean("ahoj čiarka ako sa máš otáznik", "sk"))
    }

    @Test fun `french with end-only point`() {
        assertEquals("C'est fini.", clean("c'est fini point", "fr"))
        assertEquals("À ce point il est parti", clean("à ce point il est parti", "fr"))
        assertEquals("Oui; non", clean("oui point virgule non", "fr"))
        assertEquals("Quoi?", clean("quoi point d'interrogation", "fr"))
        assertEquals("Fin.\nSuite", clean("fin point à la ligne suite", "fr"))
    }

    @Test fun `spanish`() {
        assertEquals("Hola, qué tal?", clean("hola coma qué tal signo de interrogación", "es"))
        assertEquals("Llego a las tres en punto mañana", clean("llego a las tres en punto mañana", "es"))
        assertEquals("Fin.\nOtro", clean("fin punto y aparte otro", "es"))
    }

    @Test fun `german italian portuguese polish dutch`() {
        assertEquals("Hallo, Welt.", clean("Hallo Komma Welt Punkt", "de")) // Whisper casing kept
        assertEquals("Auf den Punkt gebracht", clean("auf den Punkt gebracht", "de"))
        assertEquals("Ciao, come stai?", clean("ciao virgola come stai punto interrogativo", "it"))
        assertEquals("Cancella il file", clean("cancella il file", "it"))
        assertEquals("Olá, tudo bem?", clean("olá vírgula tudo bem ponto de interrogação", "pt"))
        assertEquals("Cześć, jak się masz?", clean("cześć przecinek jak się masz znak zapytania", "pl"))
        assertEquals("Hallo, wereld.", clean("hallo komma wereld punt", "nl"))
    }

    @Test fun `tables never leak into other languages`() {
        assertEquals("Tečka is a Czech word", clean("tečka is a Czech word", "en"))
        assertEquals("Comma je slovo", clean("comma je slovo", "cs"))
        assertEquals("Ciao comma", clean("ciao comma", "ja")) // no table for Japanese
    }

    @Test fun `unknown language uses english commands but no i fix`() {
        assertEquals("Hi, i", clean("hi comma i", null))
    }

    @Test fun `scratch still works with punctuation commands off`() {
        assertEquals("Today comma", clean("tomorrow scratch that today comma", punctuation = false))
    }

    @Test fun `zipformer upper case is normalized`() {
        assertEquals("Hello, I am here", clean("HELLO COMMA I AM HERE".lowercase()))
    }

    @Test fun `dictionary replacement still applies`() {
        val dict = listOf(UserData.Entry("flow key", "FlowKey"))
        assertEquals("I love FlowKey.", clean("i love flow key period", dictionary = dict))
    }
}
