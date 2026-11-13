package com.flowkey.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HotwordsTest {

    @Test fun `terms become upper-case scored lines`() {
        assertEquals(listOf("GITHUB :4.0", "FLOW CLONE :4.0"), Hotwords.format(listOf("GitHub", "flow  clone")))
    }

    @Test fun `apostrophes are kept, typographic ones normalized`() {
        assertEquals(listOf("ROCK'N'ROLL :4.0", "O'NEIL :4.0"), Hotwords.format(listOf("rock'n'roll", "O’Neil")))
    }

    @Test fun `terms the english model cannot encode are dropped`() {
        assertEquals(emptyList<String>(), Hotwords.format(listOf("naïve", "Dvořák", "C++", "R2D2", "")))
    }

    @Test fun `phrases longer than six words are dropped`() {
        assertEquals(1, Hotwords.format(listOf("one two three four five six")).size)
        assertEquals(0, Hotwords.format(listOf("one two three four five six seven")).size)
    }

    @Test fun `duplicates collapse`() {
        assertEquals(listOf("KUBERNETES :4.0"), Hotwords.format(listOf("Kubernetes", "kubernetes")))
    }

    @Test fun `vocab must belong to the model tokens`() {
        val dir = createTempDir()
        try {
            val tokens = File(dir, "tokens.txt").apply { writeText("<blk> 0\n▁THE 1\nS 2\n#0 3\n") }
            val good = File(dir, "good.vocab").apply { writeText("<blk>\t0.0\n▁THE\t-1.0\nS\t-2.0\n") }
            val bad = File(dir, "bad.vocab").apply { writeText("<blk>\t0.0\n▁the\t-1.0\n") }
            val empty = File(dir, "empty.vocab").apply { writeText("") }
            assertTrue(ModelLocator.vocabMatchesTokens(good, tokens))
            assertFalse(ModelLocator.vocabMatchesTokens(bad, tokens))
            assertFalse(ModelLocator.vocabMatchesTokens(empty, tokens))
            assertFalse(ModelLocator.vocabMatchesTokens(File(dir, "missing"), tokens))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `committed vocab matches the bundled zipformer tokens when present`() {
        val vocab = File("src/main/assets/hotwords/en-2023-06-26.bpe.vocab")
        val tokens = File("src/main/assets/model/zipformer/tokens.txt")
        assertTrue("committed vocab missing", vocab.isFile)
        if (tokens.isFile) assertTrue(ModelLocator.vocabMatchesTokens(vocab, tokens))
    }
}
