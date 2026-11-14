package com.flowkey.ime

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * User dictionary (misrecognized word -> correction), snippets (spoken trigger phrase ->
 * expansion) and "your words" (names and terms to recognize), persisted as JSON in
 * SharedPreferences.
 */
object UserData {

    data class Entry(val from: String, val to: String)

    private const val FILE = "flowkey_user_data"
    private const val KEY_DICTIONARY = "dictionary"
    private const val KEY_SNIPPETS = "snippets"
    private const val KEY_WORDS = "words"
    const val MAX_WORDS = 200

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

    /** Edit an entry in place (the app's Dictionary screen). Out-of-range indexes are ignored. */
    fun updateDictionary(context: Context, index: Int, from: String, to: String) =
        save(context, KEY_DICTIONARY, load(context, KEY_DICTIONARY).mapIndexed { i, e ->
            if (i == index) Entry(from.trim(), to.trim()) else e
        })

    fun updateSnippet(context: Context, index: Int, trigger: String, expansion: String) =
        save(context, KEY_SNIPPETS, load(context, KEY_SNIPPETS).mapIndexed { i, e ->
            if (i == index) Entry(trigger.trim(), expansion.trim()) else e
        })

    /** False if empty or the same as another word. */
    fun updateWord(context: Context, index: Int, word: String): Boolean {
        val w = word.trim().replace(Regex("\\s+"), " ")
        val words = getWords(context)
        if (w.isEmpty() || index !in words.indices) return false
        if (words.withIndex().any { (i, it) -> i != index && it.equals(w, ignoreCase = true) }) return false
        saveWords(context, words.mapIndexed { i, it -> if (i == index) w else it })
        return true
    }

    fun getWords(context: Context): List<String> {
        val json = prefs(context).getString(KEY_WORDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** Adds a word or short phrase. Returns false if empty, a duplicate, or the list is full. */
    fun addWord(context: Context, word: String): Boolean {
        val w = word.trim().replace(Regex("\\s+"), " ")
        val words = getWords(context)
        if (w.isEmpty() || words.size >= MAX_WORDS) return false
        if (words.any { it.equals(w, ignoreCase = true) }) return false
        saveWords(context, words + w)
        return true
    }

    fun removeWord(context: Context, index: Int) =
        saveWords(context, getWords(context).filterIndexed { i, _ -> i != index })

    /**
     * Terms FlowKey should recognize: "your words" plus every dictionary correction target.
     * Used both for correcting Whisper output and as hotwords for the streaming engine.
     */
    fun userTerms(context: Context): List<String> =
        (getWords(context) + getDictionary(context).map { it.to.trim() })
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    private fun saveWords(context: Context, words: List<String>) {
        val array = JSONArray()
        for (w in words) array.put(w)
        prefs(context).edit().putString(KEY_WORDS, array.toString()).apply()
    }

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
