package com.flowkey.ime.core

import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * One dictation in the history — the same JSON-lines format as the Windows app
 * (windows/flowclone/history.py): {id, ts, app, raw, text, words, duration_s, style}.
 */
data class HistoryEntry(
    val id: String,
    val ts: String,          // "yyyy-MM-dd HH:mm:ss", local time
    val app: String,         // target app package ("" when unknown)
    val raw: String,
    val text: String,
    val words: Int,
    val durationS: Double,
    val style: String?,
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("id", id); put("ts", ts); put("app", app); put("raw", raw); put("text", text)
        put("words", words); put("duration_s", durationS)
        put("style", style ?: JSONObject.NULL)
    }.toString()

    /** Milliseconds since the epoch (0 if the timestamp is unreadable). */
    fun epochMillis(): Long = try {
        HistoryFormat.tsFormat().parse(ts)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}

object HistoryFormat {
    fun tsFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun newEntry(text: String, raw: String, app: String?, durationS: Double, style: String?,
                 now: Date = Date()): HistoryEntry =
        HistoryEntry(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            ts = tsFormat().format(now),
            app = app.orEmpty(),
            raw = raw,
            text = text,
            words = StatsCalc.countWords(text),
            durationS = Math.round(durationS * 100) / 100.0,
            style = style,
        )

    fun legacyId(ts: String, raw: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$ts\u0000$raw\u0000$text".toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(12)
    }

    /** Parse one line; repairs wrong types, returns null for lines that aren't objects. */
    fun parse(line: String): HistoryEntry? {
        if (line.isBlank()) return null
        val o = try { JSONObject(line) } catch (e: Exception) { return null }
        fun str(k: String) = (o.opt(k) as? String).orEmpty()
        val ts = str("ts")
        val raw = str("raw")
        val text = str("text")
        val id = str("id").ifBlank { legacyId(ts, raw, text) }
        val words = (o.opt("words") as? Int) ?: StatsCalc.countWords(text)
        val dur = (o.opt("duration_s") as? Number)?.toDouble() ?: 0.0
        return HistoryEntry(id, ts, str("app"), raw, text, words, dur, o.opt("style") as? String)
    }

    /** Old FlowKey history (SharedPreferences): {text, ts(epoch millis)}. */
    fun fromLegacy(text: String, tsMillis: Long): HistoryEntry {
        val ts = tsFormat().format(Date(tsMillis))
        return HistoryEntry(legacyId(ts, "", text), ts, "", "", text, StatsCalc.countWords(text), 0.0, null)
    }

    /** Parse many lines (oldest first) and give identical old lines distinct ids. */
    fun parseAll(lines: List<String>): List<HistoryEntry> {
        val seen = HashSet<String>()
        return lines.mapNotNull { parse(it) }.map { e ->
            var id = e.id
            var n = 1
            while (!seen.add(id)) id = "${e.id}-${n++}"
            if (id == e.id) e else e.copy(id = id)
        }
    }

    fun search(entries: List<HistoryEntry>, query: String, app: String? = null): List<HistoryEntry> {
        val q = query.trim().lowercase()
        return entries.filter { e ->
            (app == null || e.app == app) &&
                (q.isEmpty() || q in e.text.lowercase() || q in e.raw.lowercase())
        }
    }
}
