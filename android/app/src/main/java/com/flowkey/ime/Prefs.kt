package com.flowkey.ime

import android.content.Context
import android.content.SharedPreferences

/** App settings persisted in SharedPreferences. */
object Prefs {

    /** Cycle order for the IME language key. "auto" = Whisper per-utterance auto-detect. */
    val LANGUAGES = listOf("auto", "en", "cs", "fr", "es")

    /** Auto-stop silence duration choices, in milliseconds (UI shows seconds). */
    val AUTO_STOP_CHOICES_MS = listOf(1500L, 2000L, 2500L, 3000L, 4000L)

    private const val FILE = "flowkey_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_STREAMING = "streaming_mode"
    private const val KEY_STREAMING_LOAD_PENDING = "streaming_load_pending"
    private const val KEY_STREAMING_CRASH_NOTICE = "streaming_crash_notice"
    private const val KEY_AUTO_STOP = "auto_stop_enabled"
    private const val KEY_AUTO_STOP_MS = "auto_stop_ms"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_PUNCTUATION_COMMANDS = "punctuation_commands_enabled"
    private const val KEY_FILLER_REMOVAL = "filler_removal_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- language

    fun getLanguage(context: Context): String {
        val value = prefs(context).getString(KEY_LANGUAGE, "auto") ?: "auto"
        return if (value in LANGUAGES) value else "auto"
    }

    fun setLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun nextLanguage(context: Context): String {
        val next = LANGUAGES[(LANGUAGES.indexOf(getLanguage(context)) + 1) % LANGUAGES.size]
        setLanguage(context, next)
        return next
    }

    /** The value passed to OfflineWhisperModelConfig.language ("" = auto-detect). */
    fun whisperLanguage(context: Context): String =
        getLanguage(context).let { if (it == "auto") "" else it }

    // --------------------------------------------------------------- engine

    /** Optional secondary engine: English-only streaming Zipformer with live partials. */
    fun isStreamingMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAMING, false)

    fun setStreamingMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAMING, enabled).apply()
    }

    /**
     * Crash guard for the streaming engine. A bad model can make sherpa-onnx terminate the
     * whole process from native code, which no try/catch sees. The flag is written
     * synchronously before the native load and cleared after it; if it is still set on the
     * next start, the previous load killed the process.
     */
    fun setStreamingLoadPending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAMING_LOAD_PENDING, pending).commit()
    }

    fun isStreamingLoadPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAMING_LOAD_PENDING, false)

    /** Turns streaming off after a crashed load and leaves a one-time notice for Setup. */
    fun disableStreamingAfterCrash(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_STREAMING, false)
            .putBoolean(KEY_STREAMING_LOAD_PENDING, false)
            .putBoolean(KEY_STREAMING_CRASH_NOTICE, true)
            .commit()
    }

    /** Returns true once after [disableStreamingAfterCrash], then clears the notice. */
    fun consumeStreamingCrashNotice(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_STREAMING_CRASH_NOTICE, false)) return false
        p.edit().putBoolean(KEY_STREAMING_CRASH_NOTICE, false).apply()
        return true
    }

    // ------------------------------------------------------------ dictation

    fun isAutoStopEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_STOP, true)

    fun setAutoStopEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_STOP, enabled).apply()
    }

    fun getAutoStopMs(context: Context): Long {
        val value = prefs(context).getLong(KEY_AUTO_STOP_MS, 2500L)
        return if (value in AUTO_STOP_CHOICES_MS) value else 2500L
    }

    fun setAutoStopMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_AUTO_STOP_MS, ms).apply()
    }

    // ------------------------------------------------------------- feedback

    fun isHapticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    fun isSoundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    // -------------------------------------------------------------- cleanup

    fun isPunctuationCommandsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PUNCTUATION_COMMANDS, true)

    fun setPunctuationCommandsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PUNCTUATION_COMMANDS, enabled).apply()
    }

    fun isFillerRemovalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILLER_REMOVAL, true)

    fun setFillerRemovalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FILLER_REMOVAL, enabled).apply()
    }
}
