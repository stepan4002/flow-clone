package com.flowkey.ime.core

/**
 * "Press enter" at the very end of a dictation presses Enter after inserting (send a chat
 * message, run a search) — like the Windows app. Only at the end, so it never fires mid-text.
 */
object PressEnter {
    // Only explicit "Enter" commands: "send it" & co. are ordinary words at the end of a sentence.
    private val PHRASES = listOf(
        "press enter", "hit enter",                                       // en
        "stiskni enter", "zmáčkni enter",                                 // cs
        "appuie sur entrée", "appuyer sur entrée",                        // fr
        "pulsa intro", "pulsa enter",                                     // es
        "drücke enter", "enter drücken",                                  // de
    )
    private val TRAILING = Regex("""[\s,.!?;:…]+$""")

    /** (text without the command, whether Enter should be pressed). */
    fun extract(text: String): Pair<String, Boolean> {
        val stripped = text.replace(TRAILING, "")
        val lower = stripped.lowercase()
        for (p in PHRASES) {
            if (lower == p) return "" to true
            if (lower.endsWith(" $p") || lower.endsWith(",$p") || lower.endsWith(".$p")) {
                val cut = stripped.substring(0, stripped.length - p.length).replace(TRAILING, "")
                return cut to true
            }
        }
        return text to false
    }
}
