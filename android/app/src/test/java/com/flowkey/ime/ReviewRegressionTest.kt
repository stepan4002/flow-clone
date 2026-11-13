package com.flowkey.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regressions found by the v1.2 deep review; each case failed before its fix. */
class ReviewRegressionTest {

    private fun clean(raw: String, lang: String?, words: List<String> = emptyList()) =
        TextCleanup.process(raw, true, emptyList(), emptyList(), commandLanguage = lang, words = words, correctWords = true)

    @Test fun `grammatical forms of a term are not corrected`() {
        assertEquals("the pipelines are green", WordCorrector.apply("the pipelines are green", listOf("Pipeline"), true))
        assertEquals("Kubernetes's API", WordCorrector.apply("Kubernetes's API", listOf("Kubernetes"), true))
        assertEquals("Používám Microsoftu", clean("Používám Microsoftu", "cs", listOf("Microsoft")))
        assertEquals("Ahoj Kateřino", clean("Ahoj Kateřino", "cs", listOf("Kateřina"))) // no fuzzy in cs
        assertEquals("Deploy to Kubernetes", clean("Deploy to Kubernetis", "en", listOf("Kubernetes")))
    }

    @Test fun `short and capitalized terms leave ordinary words alone`() {
        assertEquals("Is it ready", clean("is it ready", "en", listOf("IT")))
        assertEquals("You may go", clean("you may go", "en", listOf("May")))
        assertEquals("I pushed to GitHub", clean("i pushed to github", "en", listOf("GitHub")))
        assertEquals("Ask Dvořák", clean("ask dvorak", "en", listOf("Dvořák"))) // accents still fixed
    }

    @Test fun `term matches never cross a line break`() {
        assertEquals("new\n\nYork is big", WordCorrector.apply("new\n\nYork is big", listOf("New York"), true))
    }

    @Test fun `fillers are only removed where they are not words`() {
        assertEquals("Eu tenho um carro", clean("eu tenho um carro", "pt"))
        assertEquals("Er ist da", clean("er ist da", "de"))
        assertEquals("Que esté bien", clean("que esté bien", "es"))
        assertEquals("So we go", clean("um so we go", "en"))
        assertEquals("Hello", clean("hmm hello", "de"))
    }

    @Test fun `a phrase break counts as the end for end-only commands`() {
        val b = TextCleanup.PHRASE_BREAK
        assertEquals("Bis morgen. Dann gehen wir", clean("bis morgen Punkt $b dann gehen wir", "de"))
        assertEquals("Auf den Punkt gebracht", clean("auf den Punkt gebracht", "de"))
        assertEquals("One two", clean("one $b two", "en")) // the marker never shows
    }

    @Test fun `long audio is split into equal chunks`() {
        val chunks = WhisperChunks.split(FloatArray(16000 * 28 + 800))
        assertEquals(2, chunks.size)
        assertEquals(chunks[0].size, chunks[1].size)
    }
}
