package com.flowkey.ime.data

import android.content.Context
import com.flowkey.ime.core.HistoryEntry
import com.flowkey.ime.core.HistoryFormat
import com.flowkey.ime.core.StatsCalc
import com.flowkey.ime.core.StatsData
import com.flowkey.ime.core.StyleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * All history / stats writes run on one background thread, in order: the keyboard never does
 * file I/O on its main thread, and the app's screens (same process) see changes via [version].
 */
object StoreScope {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flowkey-store").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    fun changed() { _version.value = _version.value + 1 }
    fun launch(block: () -> Unit) {
        scope.launch { block(); changed() }
    }
}

internal fun atomicWrite(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) throw java.io.IOException("could not replace ${file.name}")
    }
}

/**
 * Dictation history: `filesDir/history.jsonl` in the Windows app's format (see HistoryFormat),
 * newest last on disk, up to [LIMIT] entries. Older FlowKey versions kept 50 entries in
 * SharedPreferences; they are moved here once.
 */
object HistoryStore {
    const val LIMIT = 1000
    private const val LEGACY_PREFS = "flowkey_history"
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "history.jsonl")

    private fun readLines(ctx: Context): List<String> = synchronized(lock) {
        migrate(ctx)
        val f = file(ctx)
        if (!f.exists()) emptyList() else try { f.readLines(Charsets.UTF_8) } catch (e: Exception) { emptyList() }
    }

    /** Newest first. Call off the main thread. */
    fun entries(ctx: Context): List<HistoryEntry> = HistoryFormat.parseAll(readLines(ctx)).asReversed()

    fun search(ctx: Context, query: String, app: String? = null): List<HistoryEntry> =
        HistoryFormat.search(entries(ctx), query, app)

    fun last(ctx: Context): HistoryEntry? = entries(ctx).firstOrNull()

    /** Asynchronous; safe from the keyboard's main thread. */
    fun add(ctx: Context, entry: HistoryEntry) {
        val app = ctx.applicationContext
        StoreScope.launch {
            synchronized(lock) {
                migrate(app)
                try {
                    val f = file(app)
                    f.appendText(entry.toJsonLine() + "\n", Charsets.UTF_8)
                    val lines = f.readLines(Charsets.UTF_8)
                    if (lines.size > LIMIT * 2) atomicWrite(f, lines.takeLast(LIMIT).joinToString("\n", postfix = "\n"))
                } catch (e: Exception) {
                    // history is best-effort; never crash the keyboard
                }
            }
        }
    }

    fun delete(ctx: Context, id: String) {
        val app = ctx.applicationContext
        StoreScope.launch {
            synchronized(lock) {
                runCatching {
                    // Read errors abort the delete (readLines() would return nothing and the
                    // rewrite would wipe the history); nothing to remove → no rewrite.
                    val f = file(app)
                    if (!f.exists()) return@runCatching
                    val all = HistoryFormat.parseAll(f.readLines(Charsets.UTF_8))
                    val kept = all.filter { it.id != id }
                    if (kept.size == all.size) return@runCatching
                    atomicWrite(f, kept.joinToString("") { it.toJsonLine() + "\n" })
                }
            }
        }
    }

    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        StoreScope.launch { synchronized(lock) { runCatching { atomicWrite(file(app), "") } } }
    }

    /** One-time move of the old SharedPreferences history (verified before the old key goes). */
    private fun migrate(ctx: Context) {
        val prefs = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("entries", null) ?: return
        val old = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val t = o.optString("text")
                    if (t.isBlank()) null else HistoryFormat.fromLegacy(t, o.optLong("ts"))
                }
            }.asReversed() // old list was newest first; the file is oldest first
        } catch (e: Exception) {
            emptyList()
        }
        try {
            val f = file(ctx)
            val existing = if (f.exists()) f.readText(Charsets.UTF_8) else ""
            atomicWrite(f, old.joinToString("") { it.toJsonLine() + "\n" } + existing)
            if (HistoryFormat.parseAll(f.readLines(Charsets.UTF_8)).size >= old.size) {
                // commit(), not apply(): if the process died before an async write reached the
                // disk, the next start would migrate (and duplicate) the old entries again.
                prefs.edit().remove("entries").commit()
            }
        } catch (e: Exception) {
            // keep the old data; try again next time
        }
    }
}

/** Insights (`filesDir/stats.json`, same formulas as Windows). */
object StatsStore {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "stats.json")

    /** A snapshot for the screens. Call off the main thread. */
    fun load(ctx: Context): StatsCalc = synchronized(lock) {
        val f = file(ctx)
        StatsCalc(StatsData.fromJson(if (f.exists()) runCatching { f.readText() }.getOrNull() else null))
    }

    fun add(ctx: Context, text: String, seconds: Double, app: String?) {
        val a = ctx.applicationContext
        StoreScope.launch {
            synchronized(lock) {
                val calc = load(a)
                calc.add(text, seconds, app)
                runCatching { atomicWrite(file(a), calc.data.toJson()) }
            }
        }
    }
}

/** Per-app styles, saved in the settings file. */
object StyleStore {
    private const val KEY = "styles_json"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("flowkey_settings", Context.MODE_PRIVATE)
    fun load(ctx: Context): StyleConfig = StyleConfig.fromJson(prefs(ctx).getString(KEY, null))
    fun save(ctx: Context, cfg: StyleConfig) {
        prefs(ctx).edit().putString(KEY, cfg.toJson()).apply()
        StoreScope.changed()
    }
}
