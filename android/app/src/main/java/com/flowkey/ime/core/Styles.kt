package com.flowkey.ime.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-app writing styles — the same ids and rules as the Windows app (windows/flowclone/styles.py).
 *
 * - verbatim: your words as spoken (no filler removal, no word correction); spoken punctuation
 *   commands and "press enter" still work because they are explicit commands
 * - clean:    the normal rules
 * - casual:   clean, and no full stop after a one-sentence message
 * - formal:   clean, never drops the full stop
 *
 * Apps are identified by the target field's package name (EditorInfo.packageName).
 */
object Styles {
    const val VERBATIM = "verbatim"
    const val CLEAN = "clean"
    const val CASUAL = "casual"
    const val FORMAL = "formal"
    val ALL = listOf(VERBATIM, CLEAN, CASUAL, FORMAL)
    val CATEGORIES = listOf("messaging", "email", "code", "documents")

    private const val SAME_LANGUAGE = " Reply in the same language as the transcript."
    private val INSTRUCTIONS = mapOf(
        VERBATIM to "Fix only obvious speech-recognition errors; keep every word, the word order " +
            "and the speaker's own phrasing.",
        CLEAN to "Remove filler words, false starts and repetitions, fix grammar and punctuation, " +
            "and keep the speaker's meaning and voice.",
        CASUAL to "Make it read like a natural chat message: relaxed and concise, no greeting or " +
            "sign-off, no full stop after a single sentence.",
        FORMAL to "Make it read like a clear, polite, well-structured professional message with " +
            "complete sentences and correct punctuation.",
    )

    fun llmInstruction(style: String): String =
        (INSTRUCTIONS[style] ?: INSTRUCTIONS.getValue(CLEAN)) + SAME_LANGUAGE

    fun defaultConfig(): StyleConfig = StyleConfig(
        enabled = true,
        default = CLEAN,
        categories = linkedMapOf(
            "messaging" to Category(CASUAL, listOf(
                "com.whatsapp", "org.telegram.messenger", "com.slack", "com.discord",
                "com.google.android.apps.messaging", "com.facebook.orca",
                "org.thoughtcrime.securesms", "com.samsung.android.messaging",
                "com.microsoft.teams")),
            "email" to Category(FORMAL, listOf(
                "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android",
                "com.samsung.android.email.provider", "com.fsck.k9")),
            "code" to Category(VERBATIM, listOf("com.termux", "com.foxdebug.acode", "com.aide.ui")),
            "documents" to Category(CLEAN, listOf(
                "com.google.android.apps.docs.editors.docs", "com.microsoft.office.word",
                "md.obsidian", "notion.id", "com.google.android.keep",
                "com.samsung.android.app.notes")),
        ),
    )

    fun categoryFor(pkg: String?, cfg: StyleConfig): String? {
        val p = pkg?.lowercase() ?: return null
        return cfg.categories.entries.firstOrNull { (_, c) -> c.apps.any { it.lowercase() == p } }?.key
    }

    fun styleFor(pkg: String?, cfg: StyleConfig): String {
        if (!cfg.enabled) return CLEAN
        val style = categoryFor(pkg, cfg)?.let { cfg.categories[it]?.style } ?: cfg.default
        return if (style in ALL) style else CLEAN
    }

    /**
     * The style's final touch on rule-cleaned text. Casual drops the full stop of a one-sentence
     * text, but only for a whole dictation: applied per streaming segment it would glue the
     * sentences of a longer dictation together.
     */
    fun finish(text: String, style: String, wholeDictation: Boolean): String {
        if (style != CASUAL || !wholeDictation) return text
        val t = text.trimEnd()
        if (!t.endsWith(".") || t.endsWith("..") || '\n' in t) return text
        if (t.count { it == '.' || it == '!' || it == '?' } != 1) return text
        return t.dropLast(1) + text.substring(t.length)
    }
}

data class Category(val style: String, val apps: List<String>)

data class StyleConfig(
    val enabled: Boolean,
    val default: String,
    val categories: LinkedHashMap<String, Category>,
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("default", default)
        put("categories", JSONObject().apply {
            categories.forEach { (name, c) ->
                put(name, JSONObject().put("style", c.style).put("apps", JSONArray(c.apps)))
            }
        })
    }.toString()

    fun withStyle(category: String, style: String): StyleConfig =
        copy(categories = LinkedHashMap(categories).apply {
            this[category]?.let { this[category] = it.copy(style = style) }
        })

    /** Put [pkg] into [category] (an app belongs to one category only). */
    fun withApp(category: String, pkg: String): StyleConfig {
        val p = pkg.lowercase()
        val out = LinkedHashMap<String, Category>()
        categories.forEach { (name, c) ->
            val apps = c.apps.filter { it.lowercase() != p }
            out[name] = c.copy(apps = if (name == category) apps + pkg else apps)
        }
        return copy(categories = out)
    }

    fun withoutApp(pkg: String): StyleConfig {
        val p = pkg.lowercase()
        val out = LinkedHashMap<String, Category>()
        categories.forEach { (name, c) -> out[name] = c.copy(apps = c.apps.filter { it.lowercase() != p }) }
        return copy(categories = out)
    }

    companion object {
        /** Parse saved JSON; anything missing or malformed falls back to the defaults. */
        fun fromJson(json: String?): StyleConfig {
            val d = Styles.defaultConfig()
            if (json.isNullOrBlank()) return d
            return try {
                val o = JSONObject(json)
                val cats = LinkedHashMap(d.categories)
                o.optJSONObject("categories")?.let { co ->
                    for (name in Styles.CATEGORIES) {
                        val c = co.optJSONObject(name) ?: continue
                        val style = c.optString("style").takeIf { it in Styles.ALL }
                            ?: d.categories.getValue(name).style
                        val arr = c.optJSONArray("apps")
                        val apps = if (arr == null) d.categories.getValue(name).apps
                        else (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { s -> s.isNotBlank() } }
                        cats[name] = Category(style, apps)
                    }
                }
                StyleConfig(
                    enabled = o.optBoolean("enabled", true),
                    default = o.optString("default").takeIf { it in Styles.ALL } ?: Styles.CLEAN,
                    categories = cats,
                )
            } catch (e: Exception) {
                d
            }
        }
    }
}
