package com.flowkey.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class WordCorrectorTest {

    private fun fix(text: String, vararg terms: String, fuzzy: Boolean = true) =
        WordCorrector.apply(text, terms.toList(), fuzzy)

    @Test fun `exact matches get the user's casing`() {
        assertEquals("I pushed it to GitHub", fix("I pushed it to github", "GitHub"))
        assertEquals("GitHub, then", fix("github, then", "GitHub"))
        assertEquals("Ask Dvořák", fix("Ask dvorak", "Dvořák")) // accent-insensitive
    }

    @Test fun `multi-word terms`() {
        assertEquals("open Flow Clone now", fix("open flow clone now", "Flow Clone"))
        assertEquals("open Flow Clone now", fix("open flow clown now", "Flow Clone"))
        // Not across punctuation.
        assertEquals("flow. clone", fix("flow. clone", "Flow Clone", fuzzy = false))
    }

    @Test fun `close misspellings of long terms are fixed`() {
        assertEquals("Deploy to Kubernetes", fix("Deploy to Kubernetis", "Kubernetes"))
        assertEquals("Ask Kowalski", fix("Ask Kowalsky", "Kowalski"))
    }

    @Test fun `short terms and common words are not fuzzy-matched`() {
        assertEquals("Nikoss", fix("Nikoss", "Nikos"))     // 5 letters: exact only
        assertEquals("The road was paved", fix("The road was paved", "Pavel"))
        assertEquals("The bird flew", fix("The bird flew", "Flow"))
        assertEquals("look at the periodic table", fix("look at the periodic table"))
    }

    @Test fun `first letter must match`() {
        assertEquals("Ubernetes", fix("Ubernetes", "Kubernetes"))
    }

    @Test fun `fuzzy off keeps only exact casing`() {
        assertEquals("Kubernetis on GitHub", fix("Kubernetis on github", "Kubernetes", "GitHub", fuzzy = false))
    }

    @Test fun `longer terms win over shorter ones`() {
        assertEquals("Flow Clone rocks", fix("flow clone rocks", "Flow", "Flow Clone"))
    }

    @Test fun `terms that are not plain words are ignored safely`() {
        assertEquals("I write c++ daily", fix("I write c++ daily", "C++"))
        assertEquals("costs \$5", fix("costs \$5", "a.b", "\$"))
        assertEquals("rock'n'roll", fix("Rock'n'roll", "rock'n'roll"))
    }

    @Test fun `edit distance gives up past the limit`() {
        assertEquals(1, WordCorrector.levenshtein("kitten", "sitten", 2))
        assertEquals(3, WordCorrector.levenshtein("kitten", "sitting", 2))
    }
}
