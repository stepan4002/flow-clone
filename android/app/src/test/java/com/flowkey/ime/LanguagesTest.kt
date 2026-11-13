package com.flowkey.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    @Test fun `whisper list has 99 unique codes`() {
        assertEquals(99, Languages.WHISPER_CODES.size)
        assertEquals(99, Languages.WHISPER_CODES.toSet().size)
    }

    @Test fun `known codes are valid and the parsing artefact is not`() {
        for (c in listOf("en", "cs", "fr", "es", "sk", "de", "da", "haw", "jw")) {
            assertTrue(c, Languages.isValid(c))
        }
        for (c in listOf("dar", "xx", "", "EN", "auto")) assertFalse(c, Languages.isValid(c))
        assertFalse(Languages.isValid(null))
    }

    @Test fun `display names are English with the native name`() {
        assertTrue(Languages.displayName("cs").startsWith("Czech"))
        assertEquals("English", Languages.displayName("en"))
        assertTrue(Languages.displayName("jw").startsWith("Javanese"))
    }

    @Test fun `detected language is normalized or dropped`() {
        assertEquals("en", Languages.normalizeDetected("<|en|>"))
        assertEquals("en", Languages.normalizeDetected("EN"))
        assertEquals("cs", Languages.normalizeDetected(" cs "))
        assertNull(Languages.normalizeDetected(""))
        assertNull(Languages.normalizeDetected(null))
        assertNull(Languages.normalizeDetected("xx"))
    }

    @Test fun `enabled list is validated and de-duplicated`() {
        assertEquals(listOf("en", "cs"), Languages.parseEnabled("en,xx,en, cs"))
        assertEquals(Languages.PRESET, Languages.parseEnabled(null))
        assertEquals(Languages.PRESET, Languages.parseEnabled("xx,yy"))
    }

    @Test fun `language key cycles through auto and the enabled list`() {
        val enabled = listOf("en", "cs")
        assertEquals("en", Languages.next("auto", enabled))
        assertEquals("cs", Languages.next("en", enabled))
        assertEquals("auto", Languages.next("cs", enabled))
        assertEquals("auto", Languages.next("fr", enabled)) // removed language
    }

    @Test fun `removing the active language falls back to auto`() {
        assertEquals("auto", Languages.afterRemove("cs", "cs"))
        assertEquals("en", Languages.afterRemove("en", "cs"))
    }
}
