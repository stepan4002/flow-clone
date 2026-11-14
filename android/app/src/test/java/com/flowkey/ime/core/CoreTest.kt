package com.flowkey.ime.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.time.LocalDate

class StylesTest {
    private val cfg = Styles.defaultConfig()

    @Test fun `apps map to their category style`() {
        assertEquals(Styles.CASUAL, Styles.styleFor("com.whatsapp", cfg))
        assertEquals(Styles.FORMAL, Styles.styleFor("com.google.android.gm", cfg))
        assertEquals(Styles.VERBATIM, Styles.styleFor("com.termux", cfg))
        assertEquals(Styles.CLEAN, Styles.styleFor("com.example.other", cfg))
        assertEquals(Styles.CLEAN, Styles.styleFor(null, cfg))
        assertEquals(Styles.CLEAN, Styles.styleFor("com.whatsapp", cfg.copy(enabled = false)))
    }

    @Test fun `casual drops the full stop only for a whole one-sentence dictation`() {
        assertEquals("See you at 3", Styles.finish("See you at 3.", Styles.CASUAL, true))
        assertEquals("See you at 3.", Styles.finish("See you at 3.", Styles.CASUAL, false))
        assertEquals("Hi. See you.", Styles.finish("Hi. See you.", Styles.CASUAL, true))
        assertEquals("Thanks.", Styles.finish("Thanks.", Styles.FORMAL, true))
    }

    @Test fun `config round trip and app moves`() {
        val moved = cfg.withApp("email", "com.whatsapp")
        assertEquals("email", Styles.categoryFor("com.whatsapp", moved))
        val back = StyleConfig.fromJson(moved.withStyle("code", Styles.CLEAN).toJson())
        assertEquals("email", Styles.categoryFor("com.whatsapp", back))
        assertEquals(Styles.CLEAN, back.categories.getValue("code").style)
        assertEquals(cfg, StyleConfig.fromJson("not json"))
        assertNull(Styles.categoryFor("com.whatsapp", cfg.withoutApp("COM.WHATSAPP")))
    }

    @Test fun `instructions keep the language`() {
        Styles.ALL.forEach { assertTrue(Styles.llmInstruction(it).contains("same language")) }
    }
}

class PressEnterTest {
    @Test fun `only an explicit enter command at the end`() {
        assertEquals("git status" to true, PressEnter.extract("git status press enter."))
        assertEquals("Ahoj" to true, PressEnter.extract("Ahoj, stiskni enter"))
        assertEquals("can you send it" to false, PressEnter.extract("can you send it"))
        assertEquals("press enter to continue" to false, PressEnter.extract("press enter to continue"))
        assertEquals("" to true, PressEnter.extract("Press enter."))
    }
}

class StatsCalcTest {
    @Test fun `words, streak, speed and time saved`() {
        var day = LocalDate.of(2026, 12, 31)
        val s = StatsCalc(clock = { day })
        s.add("one two three", seconds = 30.0, app = "com.whatsapp")
        assertNull(s.wpm())
        day = day.plusDays(1)                                    // year boundary
        s.add(List(117) { "w" }.joinToString(" "), seconds = 30.0, app = "com.termux")
        assertEquals(2 to 2, s.streak())
        assertEquals(120, s.wpm())
        assertEquals(2, s.timeSavedMinutes(40))
        assertEquals(listOf("com.termux" to 117), s.topApps(1))
        day = day.plusDays(1)
        assertEquals(2, s.streak().first)                        // nothing yet today: still counts
        day = day.plusDays(1)
        assertEquals(0 to 2, s.streak())
        assertEquals(4, s.daysSeries(4).size)
        assertEquals("2026-12-31" to 3, s.daysSeries(4).first())
    }

    @Test fun `CJK counts characters and json round trip repairs bad values`() {
        assertEquals(3, StatsCalc.countWords("今日は"))
        assertEquals(3, StatsCalc.countWords("ok 你好"))
        val d = StatsData.fromJson("""{"total_words": "x", "days": [], "apps": {"a": 5, "b": "?"}}""")
        assertEquals(0, d.totalWords)
        assertEquals(mapOf("a" to 5), d.apps)
        val s = StatsCalc(d)
        s.add("hello world", 2.0, "a")
        assertEquals(2, StatsData.fromJson(d.toJson()).totalWords)
    }
}

class HistoryFormatTest {
    @Test fun `new entries, legacy lines, repairs and duplicates`() {
        val e = HistoryFormat.newEntry("Hello world", "hello world", "com.whatsapp", 2.345, "casual")
        val back = HistoryFormat.parse(e.toJsonLine())!!
        assertEquals(e, back)
        assertEquals(2.35, back.durationS, 0.0)
        val legacy = """{"ts":"2026-01-01 10:00:00","app":"x","raw":"ok","text":"ok"}"""
        val parsed = HistoryFormat.parseAll(listOf(legacy, legacy, "garbage", """{"text": null}"""))
        assertEquals(3, parsed.size)
        assertEquals(3, parsed.map { it.id }.toSet().size)
        assertEquals("", parsed[2].text)
        val old = HistoryFormat.fromLegacy("Hi there", 1_700_000_000_000)
        assertEquals(2, old.words)
        assertEquals(listOf(e), HistoryFormat.search(listOf(e, old), "HELLO"))
        assertEquals(listOf(e), HistoryFormat.search(listOf(e, old), "", "com.whatsapp"))
    }
}

class GuardsTest {
    @Test fun `private fields`() {
        assertTrue(Privacy.isPrivateField(0x1 or 0x80, 0))           // text password
        assertTrue(Privacy.isPrivateField(0x1 or 0xe0, 0))           // web password
        assertTrue(Privacy.isPrivateField(0x2 or 0x10, 0))           // number password
        assertTrue(Privacy.isPrivateField(0x1, Privacy.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(Privacy.isPrivateField(0x1 or 0x20, 0))          // email address
        assertFalse(Privacy.isPrivateField(0x1, 0))
    }

    @Test fun `local addresses`() {
        listOf("127.0.0.1", "10.0.2.2", "192.168.1.20", "172.20.0.5", "100.100.1.1", "::1", "fd00::1", "169.254.1.1")
            .forEach { assertTrue(it, LocalAddress.isPrivateOrLoopback(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860::8888")
            .forEach { assertFalse(it, LocalAddress.isPrivateOrLoopback(InetAddress.getByName(it))) }
    }

    @Test fun `undo and selection guards`() {
        assertTrue(UndoPolicy.canUndo(" hello ", " hello ", 10, 10, 10, 3, 3))
        assertFalse(UndoPolicy.canUndo(" hello ", " hello ", 11, 11, 10, 3, 3))   // cursor moved
        assertFalse(UndoPolicy.canUndo(" hello ", " hellO ", 10, 10, 10, 3, 3))   // text changed
        assertFalse(UndoPolicy.canUndo(" hello ", " hello ", 10, 10, 10, 3, 4))   // other field
        assertTrue(SelectionGuard.complete("abc", 5, 8))
        assertFalse(SelectionGuard.complete("ab", 5, 8))                          // shortened
        assertFalse(SelectionGuard.complete(null, 5, 8))
        assertFalse(SelectionGuard.unchanged(1, 4, 1, 5, 2, 2))
    }
}

class LlmClientTest {
    private class FakeTransport(vararg responses: Pair<Int, String>) : LlmClient.Transport {
        val queue = ArrayDeque(responses.toList())
        val calls = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()
        var lastReadMs = 0
        override fun post(url: String, headers: Map<String, String>, body: String, connectMs: Int, readMs: Int): Pair<Int, String> {
            calls += Triple(url, headers, JSONObject(body))
            lastReadMs = readMs
            return queue.removeFirst()
        }
    }

    private fun openai(content: Any?, finish: String = "stop") = 200 to JSONObject().put("choices",
        org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content ?: JSONObject.NULL))
            .put("finish_reason", finish))).toString()

    private val key = "sk-test-SECRET"

    @Test fun `openai https sends the key, http never does`() {
        val t = FakeTransport(openai("We meet at 3."))
        val c = LlmClient(LlmConfig(provider = "openai"), t) { key }
        assertEquals("We meet at 3.", c.rewrite("um we meet at three", "x", budgetMs = 8000))
        assertEquals("https://api.openai.com/v1/chat/completions", t.calls[0].first)
        assertEquals("Bearer $key", t.calls[0].second["Authorization"])
        assertEquals(5000, t.lastReadMs)
        val local = FakeTransport(openai("Hi."))
        val lc = LlmClient(LlmConfig(provider = "openai", apiBase = "http://192.168.1.5:1234/v1"), local) { key }
        assertEquals("Hi.", lc.rewrite("hi", "x"))
        assertNull(local.calls[0].second["Authorization"])
    }

    @Test fun `key slots per service`() {
        assertEquals("openai", LlmClient.keySlot(LlmConfig(provider = "openai")))
        assertEquals("openai@api.groq.com",
            LlmClient.keySlot(LlmConfig(provider = "openai", apiBase = "https://api.groq.com/openai/v1")))
        assertEquals("anthropic", LlmClient.keySlot(LlmConfig(provider = "anthropic")))
    }

    @Test fun `bad answers give null, never an exception`() {
        val c = LlmClient(LlmConfig(provider = "openai"),
            FakeTransport(openai("Cut", "length"), 500 to "", 200 to "[]", openai(null),
                          openai("x".repeat(500)), 401 to "{}")) { key }
        repeat(6) { assertNull(c.rewrite("short text", "x")) }
        val a = LlmClient(LlmConfig(provider = "anthropic"), FakeTransport(200 to """{"content": null}""")) { key }
        assertNull(a.rewrite("aa", "x"))
        assertNull(LlmClient(LlmConfig(provider = "anthropic"), FakeTransport()) { null }.rewrite("aa", "x"))
    }

    @Test fun `ollama, think strip and write mode budget`() {
        val t = FakeTransport(200 to """{"message": {"content": "<think>x</think>Fine."}}""")
        val c = LlmClient(LlmConfig(provider = "ollama", url = "http://192.168.1.5:11434"), t) { null }
        assertEquals("Fine.", c.rewrite("fine", "x"))
        assertEquals(false, t.calls[0].third.getBoolean("think"))
        val w = FakeTransport(openai("Dear landlord, ..."))
        LlmClient(LlmConfig(provider = "openai"), w) { key }.rewrite("write to my landlord", "", mode = "command")
        assertEquals(2048, w.calls[0].third.getInt("max_tokens"))
        val e = FakeTransport(openai("Shorter."))
        assertNotNull(LlmClient(LlmConfig(provider = "openai"), e) { key }
            .rewrite("make it shorter", "", selection = "A long sentence here.", mode = "command"))
        assertTrue(e.calls[0].third.getJSONArray("messages").getJSONObject(1).getString("content").contains("<selection>"))
    }

    @Test fun `mid-sentence answers start lower case`() {
        assertEquals("we meet at 3", LlmClient.matchCursor("We meet at 3", false))
        assertEquals("We meet at 3", LlmClient.matchCursor("We meet at 3", true))
        assertEquals("I think so", LlmClient.matchCursor("I think so", false))
        assertEquals("NASA said", LlmClient.matchCursor("NASA said", false))
    }
}
