package com.flowkey.ime

import android.content.Context
import android.content.SharedPreferences

/** App settings persisted in SharedPreferences. */
object Prefs {

    /** Auto-stop silence duration choices, in milliseconds (UI shows seconds). */
    val AUTO_STOP_CHOICES_MS = listOf(1500L, 2000L, 2500L, 3000L, 4000L)

    private const val FILE = "flowkey_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ENABLED_LANGUAGES = "enabled_languages"
    private const val KEY_STREAMING = "streaming_mode"
    private const val KEY_STREAMING_LOAD_PENDING = "streaming_load_pending"
    private const val KEY_STREAMING_CRASH_NOTICE = "streaming_crash_notice"
    private const val KEY_HOTWORDS_LOAD_PENDING = "hotwords_load_pending"
    private const val KEY_HOTWORDS_DISABLED = "hotwords_disabled_after_crash"
    private const val KEY_HOTWORDS_CRASH_NOTICE = "hotwords_crash_notice"
    private const val KEY_AUTO_STOP = "auto_stop_enabled"
    private const val KEY_AUTO_STOP_MS = "auto_stop_ms"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_PUNCTUATION_COMMANDS = "punctuation_commands_enabled"
    private const val KEY_FILLER_REMOVAL = "filler_removal_enabled"
    private const val KEY_WORD_CORRECTION = "word_correction_enabled"

    /**
     * Crash-guard flags live in their own file, which is excluded from backup and device
     * transfer (see res/xml backup_rules and data_extraction_rules): a "load pending" flag restored onto a new phone would fire a
     * false crash notice there.
     */
    private const val RUNTIME_FILE = "flowkey_runtime"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile private var runtimeMigrated = false

    /** The runtime prefs file, migrating the v1.1 crash-guard keys out of the settings file once. */
    private fun runtime(context: Context): SharedPreferences {
        val rt = context.getSharedPreferences(RUNTIME_FILE, Context.MODE_PRIVATE)
        if (!runtimeMigrated) {
            synchronized(this) {
                if (!runtimeMigrated) {
                    val old = prefs(context)
                    // Only the v1.1 keys ever lived in the settings file.
                    val keys = listOf(KEY_STREAMING_LOAD_PENDING, KEY_STREAMING_CRASH_NOTICE)
                    if (keys.any { old.contains(it) }) {
                        val e = rt.edit()
                        for (k in keys) if (old.contains(k)) e.putBoolean(k, old.getBoolean(k, false))
                        e.commit()
                        val oe = old.edit()
                        for (k in keys) oe.remove(k)
                        oe.commit()
                    }
                    runtimeMigrated = true
                }
            }
        }
        return rt
    }

    // ------------------------------------------------------------- language

    /**
     * Cycle order of the IME language key: "auto" (Whisper per-utterance auto-detect) followed
     * by the languages the user enabled. Fresh installs start with [Languages.PRESET].
     */
    fun getEnabledLanguages(context: Context): List<String> =
        listOf(Languages.AUTO) + enabledCodes(context)

    private fun enabledCodes(context: Context): List<String> =
        Languages.parseEnabled(prefs(context).getString(KEY_ENABLED_LANGUAGES, null))

    /** Adds a Whisper language to the language key. Returns false if invalid or already there. */
    fun addLanguage(context: Context, code: String): Boolean {
        val codes = enabledCodes(context)
        if (!Languages.isValid(code) || code in codes) return false
        prefs(context).edit().putString(KEY_ENABLED_LANGUAGES, Languages.toCsv(codes + code)).apply()
        return true
    }

    /**
     * Removes a language from the language key. The last language is kept. If it was the
     * active one, the key falls back to auto-detect. Returns false if nothing was removed.
     */
    fun removeLanguage(context: Context, code: String): Boolean {
        val codes = enabledCodes(context)
        if (code !in codes || codes.size <= 1) return false
        val active = getLanguage(context)
        prefs(context).edit()
            .putString(KEY_ENABLED_LANGUAGES, Languages.toCsv(codes - code))
            .putString(KEY_LANGUAGE, Languages.afterRemove(active, code))
            .apply()
        return true
    }

    fun getLanguage(context: Context): String {
        val value = prefs(context).getString(KEY_LANGUAGE, Languages.AUTO) ?: Languages.AUTO
        return if (value in getEnabledLanguages(context)) value else Languages.AUTO
    }

    fun setLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun nextLanguage(context: Context): String {
        val next = Languages.next(getLanguage(context), enabledCodes(context))
        setLanguage(context, next)
        return next
    }

    /**
     * The value passed to OfflineWhisperModelConfig.language ("" = auto-detect). Anything that
     * is not a known Whisper code becomes "", because an unknown code kills the process natively.
     */
    fun whisperLanguage(context: Context): String =
        getLanguage(context).let { if (Languages.isValid(it)) it else "" }

    // --------------------------------------------------------------- engine

    /** Optional engine: the English streaming Zipformer (live partials, hotwords). */
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
        runtime(context).edit().putBoolean(KEY_STREAMING_LOAD_PENDING, pending).commit()
    }

    fun isStreamingLoadPending(context: Context): Boolean =
        runtime(context).getBoolean(KEY_STREAMING_LOAD_PENDING, false)

    /** Turns streaming off after a crashed load and leaves a one-time notice for Setup. */
    fun disableStreamingAfterCrash(context: Context) {
        prefs(context).edit().putBoolean(KEY_STREAMING, false).commit()
        runtime(context).edit()
            .putBoolean(KEY_STREAMING_LOAD_PENDING, false)
            .putBoolean(KEY_STREAMING_CRASH_NOTICE, true)
            .commit()
    }

    /** Returns true once after [disableStreamingAfterCrash], then clears the notice. */
    fun consumeStreamingCrashNotice(context: Context): Boolean {
        val p = runtime(context)
        if (!p.getBoolean(KEY_STREAMING_CRASH_NOTICE, false)) return false
        p.edit().putBoolean(KEY_STREAMING_CRASH_NOTICE, false).apply()
        return true
    }

    /**
     * Separate crash guard for hotwords (the user's words in the streaming engine). A crash
     * while loading WITH hotwords disables only the hotwords, not streaming itself, until the
     * user changes their words.
     */
    fun setHotwordsLoadPending(context: Context, pending: Boolean) {
        runtime(context).edit().putBoolean(KEY_HOTWORDS_LOAD_PENDING, pending).commit()
    }

    fun isHotwordsLoadPending(context: Context): Boolean =
        runtime(context).getBoolean(KEY_HOTWORDS_LOAD_PENDING, false)

    fun isHotwordsDisabledAfterCrash(context: Context): Boolean =
        runtime(context).getBoolean(KEY_HOTWORDS_DISABLED, false)

    fun disableHotwordsAfterCrash(context: Context) {
        runtime(context).edit()
            .putBoolean(KEY_HOTWORDS_LOAD_PENDING, false)
            .putBoolean(KEY_HOTWORDS_DISABLED, true)
            .putBoolean(KEY_HOTWORDS_CRASH_NOTICE, true)
            .commit()
    }

    /** The user edited their words: give hotwords another try. */
    fun clearHotwordsDisabled(context: Context) {
        runtime(context).edit().putBoolean(KEY_HOTWORDS_DISABLED, false).commit()
    }

    fun consumeHotwordsCrashNotice(context: Context): Boolean {
        val p = runtime(context)
        if (!p.getBoolean(KEY_HOTWORDS_CRASH_NOTICE, false)) return false
        p.edit().putBoolean(KEY_HOTWORDS_CRASH_NOTICE, false).apply()
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

    /** Auto-correct close misspellings of the user's words (exact casing is always applied). */
    fun isWordCorrectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WORD_CORRECTION, true)

    fun setWordCorrectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WORD_CORRECTION, enabled).apply()
    }
}
