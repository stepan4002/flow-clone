package com.flowkey.ime.core

import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dictation insights — the same formulas as the Windows app (windows/flowclone/stats.py):
 * words per day (90 days kept), streak, speaking speed and time saved. Speed and time saved use
 * only dictations recorded with their duration ("timed").
 */
data class StatsData(
    var totalWords: Int = 0,
    var totalDictations: Int = 0,
    val days: MutableMap<String, Int> = mutableMapOf(),
    var timedWords: Int = 0,
    var secondsDictated: Double = 0.0,
    val apps: MutableMap<String, Int> = mutableMapOf(),
    var bestStreak: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("total_words", totalWords)
        put("total_dictations", totalDictations)
        put("days", JSONObject(days as Map<*, *>))
        put("timed_words", timedWords)
        put("seconds_dictated", secondsDictated)
        put("apps", JSONObject(apps as Map<*, *>))
        put("best_streak", bestStreak)
    }.toString()

    companion object {
        fun fromJson(json: String?): StatsData {
            if (json.isNullOrBlank()) return StatsData()
            return try {
                val o = JSONObject(json)
                fun intMap(key: String): MutableMap<String, Int> {
                    val m = mutableMapOf<String, Int>()
                    o.optJSONObject(key)?.let { jo ->
                        jo.keys().forEach { k -> val v = jo.optInt(k, -1); if (v >= 0) m[k] = v }
                    }
                    return m
                }
                StatsData(
                    totalWords = o.optInt("total_words", 0).coerceAtLeast(0),
                    totalDictations = o.optInt("total_dictations", 0).coerceAtLeast(0),
                    days = intMap("days"),
                    timedWords = o.optInt("timed_words", 0).coerceAtLeast(0),
                    secondsDictated = o.optDouble("seconds_dictated", 0.0).let { if (it.isNaN() || it < 0) 0.0 else it },
                    apps = intMap("apps"),
                    bestStreak = o.optInt("best_streak", 0).coerceAtLeast(0),
                )
            } catch (e: Exception) {
                StatsData()
            }
        }
    }
}

class StatsCalc(val data: StatsData = StatsData(), private val clock: () -> LocalDate = { LocalDate.now() }) {

    companion object {
        const val KEEP_DAYS = 90L

        // Scripts written without spaces between words: count each character as a word.
        private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\uf900-\\ufaff]")

        fun countWords(text: String): Int {
            val cjk = CJK.findAll(text).count()
            val rest = CJK.replace(text, " ")
            return cjk + rest.split(Regex("\\s+")).count { it.isNotEmpty() }
        }
    }

    fun add(text: String, seconds: Double = 0.0, app: String? = null) {
        val words = countWords(text)
        val day = clock().toString()
        data.totalWords += words
        data.totalDictations += 1
        data.days[day] = (data.days[day] ?: 0) + words
        if (seconds > 0) {
            data.timedWords += words
            data.secondsDictated = ((data.secondsDictated + seconds) * 100).roundToInt() / 100.0
        }
        if (!app.isNullOrBlank()) data.apps[app] = (data.apps[app] ?: 0) + words
        val cutoff = clock().minusDays(KEEP_DAYS).toString()
        data.days.keys.removeAll { it < cutoff }
        data.bestStreak = max(data.bestStreak, currentStreak())
    }

    fun today(): Int = data.days[clock().toString()] ?: 0

    fun thisWeek(): Int {
        val cutoff = clock().minusDays(6).toString()
        return data.days.filterKeys { it >= cutoff }.values.sum()
    }

    private fun currentStreak(): Int {
        var day = clock()
        if ((data.days[day.toString()] ?: 0) <= 0) day = day.minusDays(1) // nothing yet today
        var n = 0
        while ((data.days[day.toString()] ?: 0) > 0) {
            n++
            day = day.minusDays(1)
        }
        return n
    }

    /** (current, best) in consecutive days with at least one word. */
    fun streak(): Pair<Int, Int> {
        val cur = currentStreak()
        return cur to max(cur, data.bestStreak)
    }

    /** Words per minute of timed dictation, or null below one minute recorded. */
    fun wpm(): Int? {
        if (data.secondsDictated < 60) return null
        return (data.timedWords / (data.secondsDictated / 60)).roundToInt()
    }

    fun timeSavedMinutes(typingWpm: Int = 40): Int =
        max(0, (data.timedWords.toDouble() / typingWpm - data.secondsDictated / 60).roundToInt())

    fun topApps(n: Int = 5): List<Pair<String, Int>> =
        data.apps.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    /** Oldest first, one entry per day including days without dictation. */
    fun daysSeries(n: Int = 30): List<Pair<String, Int>> {
        val t = clock()
        return (n - 1 downTo 0).map { i ->
            val d = t.minusDays(i.toLong()).toString()
            d to (data.days[d] ?: 0)
        }
    }
}
