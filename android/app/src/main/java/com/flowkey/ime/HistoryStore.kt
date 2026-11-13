package com.flowkey.ime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Local-only dictation history: the last [MAX_ENTRIES] committed transcripts. */
object HistoryStore {

    const val MAX_ENTRIES = 50

    data class Entry(val text: String, val timestamp: Long)

    private const val TAG = "FlowKeyHistory"
    private const val FILE = "flowkey_history"
    private const val KEY_ENTRIES = "entries"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Entry> {
        val json = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { obj ->
                    val text = obj.optString("text")
                    if (text.isBlank()) null else Entry(text, obj.optLong("ts"))
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupt history JSON; resetting", e)
            emptyList()
        }
    }

    fun add(context: Context, text: String) {
        if (text.isBlank()) return
        val trimmed = (listOf(Entry(text.trim(), System.currentTimeMillis())) + getAll(context))
            .take(MAX_ENTRIES)
        save(context, trimmed)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("text", entry.text).put("ts", entry.timestamp))
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
