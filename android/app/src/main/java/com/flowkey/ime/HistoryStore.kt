package com.flowkey.ime

import android.content.Context
import com.flowkey.ime.core.HistoryFormat

/**
 * Older API over the new history ([com.flowkey.ime.data.HistoryStore], JSON lines, 1000
 * entries). Kept for the callers that only need text + time.
 */
object HistoryStore {

    data class Entry(val text: String, val timestamp: Long)

    fun getAll(context: Context): List<Entry> =
        com.flowkey.ime.data.HistoryStore.entries(context).map { Entry(it.text, it.epochMillis()) }

    fun add(context: Context, text: String) {
        if (text.isBlank()) return
        com.flowkey.ime.data.HistoryStore.add(
            context, HistoryFormat.newEntry(text.trim(), text.trim(), null, 0.0, null))
    }

    fun clear(context: Context) = com.flowkey.ime.data.HistoryStore.clear(context)
}
