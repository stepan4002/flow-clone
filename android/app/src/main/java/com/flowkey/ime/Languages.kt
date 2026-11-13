package com.flowkey.ime

import java.util.Locale

/**
 * Languages the bundled multilingual Whisper model can transcribe, plus the pure list logic
 * behind the language key. No Android dependencies, so it is unit-tested on the JVM.
 *
 * IMPORTANT: sherpa-onnx terminates the whole process (native exit) when Whisper is given a
 * language code it does not know, so every code that reaches the recognizer must pass [isValid].
 */
object Languages {

    /**
     * The 99 codes from the `all_language_codes` metadata of the Whisper base/small encoders,
     * in the model's order (read with the protobuf length prefix; note "da", not "dar").
     */
    val WHISPER_CODES: List<String> = listOf(
        "ha", "sa", "af", "az", "ml", "ln", "bn", "haw", "mt", "eu", "hr", "sq", "am", "be",
        "mi", "lt", "vi", "lb", "nl", "bg", "cs", "oc", "lo", "as", "bo", "ro", "sw", "th",
        "it", "fa", "en", "si", "de", "es", "gu", "mg", "uk", "so", "mr", "ja", "yo", "mk",
        "fi", "nn", "kk", "gl", "cy", "tk", "tg", "ms", "la", "et", "ca", "hi", "yi", "is",
        "sk", "tl", "he", "lv", "ba", "tt", "sr", "kn", "pa", "ur", "pt", "te", "jw", "el",
        "hu", "no", "zh", "ta", "ar", "my", "br", "ps", "bs", "mn", "ne", "sv", "sn", "ko",
        "sd", "fo", "fr", "ht", "ru", "sl", "id", "hy", "su", "tr", "ka", "km", "uz", "pl",
        "da",
    )

    private val CODE_SET: Set<String> = WHISPER_CODES.toSet()

    /** Languages enabled on a fresh install (the language key also always offers "auto"). */
    val PRESET: List<String> = listOf("en", "cs", "fr", "es")

    const val AUTO = "auto"

    fun isValid(code: String?): Boolean = code != null && code in CODE_SET

    /** "Czech (čeština)", "English", "Javanese", ... Falls back to the upper-case code. */
    fun displayName(code: String): String {
        when (code) {
            "jw" -> return "Javanese (Basa Jawa)"
            "haw" -> return "Hawaiian (ʻŌlelo Hawaiʻi)"
        }
        val locale = Locale.forLanguageTag(code)
        val english = locale.getDisplayLanguage(Locale.ENGLISH)
        if (english.isBlank() || english.equals(code, ignoreCase = true)) return code.uppercase()
        val native = locale.getDisplayLanguage(locale)
        return if (native.isBlank() || native.equals(english, ignoreCase = true)) {
            english
        } else {
            "$english ($native)"
        }
    }

    /**
     * Normalizes the language Whisper reports for an utterance ("en", "<|en|>", "EN") to a valid
     * code, or null when it is missing or unknown.
     */
    fun normalizeDetected(raw: String?): String? {
        val code = raw?.trim()?.removePrefix("<|")?.removeSuffix("|>")?.trim()?.lowercase()
        return if (isValid(code)) code else null
    }

    /** Parses the stored CSV of enabled languages: valid, de-duplicated, ordered; [PRESET] if none. */
    fun parseEnabled(csv: String?): List<String> {
        val codes = csv.orEmpty().split(',')
            .map { it.trim().lowercase() }
            .filter { isValid(it) }
            .distinct()
        return codes.ifEmpty { PRESET }
    }

    fun toCsv(codes: List<String>): String = codes.joinToString(",")

    /** The next entry of the language key's cycle: auto -> enabled[0] -> ... -> auto. */
    fun next(current: String, enabled: List<String>): String {
        val cycle = listOf(AUTO) + enabled
        val index = cycle.indexOf(current)
        return cycle[(index + 1).mod(cycle.size)]
    }

    /** The active language after [removed] is taken out of the list. */
    fun afterRemove(active: String, removed: String): String =
        if (active == removed) AUTO else active
}
