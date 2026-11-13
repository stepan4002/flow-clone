package com.flowkey.ime

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * User dictionary (misrecognized word -> correction) and snippets
 * (spoken trigger phrase -> expansion), persisted as JSON in SharedPreferences.
 */
object UserData {

    data class Entry(val from: String, val to: String)

    private const val FILE = "flowkey_user_data"
    private const val KEY_DICTIONARY = "dictionary"
    private const val KEY_SNIPPETS = "snippets"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getDictionary(context: Context): List<Entry> = load(context, KEY_DICTIONARY)

    fun getSnippets(context: Context): List<Entry> = load(context, KEY_SNIPPETS)

    fun addDictionary(context: Context, from: String, to: String) =
        save(context, KEY_DICTIONARY, load(context, KEY_DICTIONARY) + Entry(from.trim(), to.trim()))

    fun removeDictionary(context: Context, index: Int) =
        save(context, KEY_DICTIONARY, load(context, KEY_DICTIONARY).filterIndexed { i, _ -> i != index })

    fun addSnippet(context: Context, trigger: String, expansion: String) =
        save(context, KEY_SNIPPETS, load(context, KEY_SNIPPETS) + Entry(trigger.trim(), expansion.trim()))

    fun removeSnippet(context: Context, index: Int) =
        save(context, KEY_SNIPPETS, load(context, KEY_SNIPPETS).filterIndexed { i, _ -> i != index })

    private fun load(context: Context, key: String): List<Entry> {
        val json = prefs(context).getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { obj ->
                    val from = obj.optString("from")
                    val to = obj.optString("to")
                    if (from.isBlank()) null else Entry(from, to)
                }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun save(context: Context, key: String, entries: List<Entry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("from", entry.from).put("to", entry.to))
        }
        prefs(context).edit().putString(key, array.toString()).apply()
    }
}
