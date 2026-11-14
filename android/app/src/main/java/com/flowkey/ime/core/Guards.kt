package com.flowkey.ime.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Which fields FlowKey must treat as private: never stored, never sent to an AI. */
object Privacy {
    // android.text.InputType / EditorInfo constants (kept here so this stays JVM-testable).
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TEXT_PASSWORD = 0x00000080
    private const val TEXT_VISIBLE_PASSWORD = 0x00000090
    private const val TEXT_WEB_PASSWORD = 0x000000e0
    private const val NUMBER_PASSWORD = 0x00000010
    const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x01000000

    fun isPrivateField(inputType: Int, imeOptions: Int): Boolean {
        if (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true  // e.g. incognito
        val cls = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return when (cls) {
            TYPE_CLASS_TEXT -> variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD ||
                variation == TEXT_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == NUMBER_PASSWORD
            else -> false
        }
    }
}

/** Plain http is allowed only to servers on this phone or the local network. */
object LocalAddress {
    fun isPrivateOrLoopback(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        val b = a.address
        if (a is Inet4Address) {
            val b0 = b[0].toInt() and 0xff
            val b1 = b[1].toInt() and 0xff
            if (b0 == 100 && b1 in 64..127) return true          // 100.64/10 (CGNAT, Tailscale)
        }
        if (a is Inet6Address) {
            val b0 = b[0].toInt() and 0xff
            if (b0 and 0xfe == 0xfc) return true                  // fc00::/7 unique local
        }
        return false
    }
}

/** The undo chip may remove an insertion only if nothing changed since. */
object UndoPolicy {
    fun canUndo(
        inserted: String,
        textBeforeCursor: CharSequence?,
        selStart: Int, selEnd: Int, expectedCursor: Int,
        generation: Int, currentGeneration: Int,
    ): Boolean {
        if (inserted.isEmpty() || generation != currentGeneration) return false
        if (selStart != selEnd || selStart != expectedCursor) return false
        return textBeforeCursor != null && textBeforeCursor.toString() == inserted
    }
}

/** A voice edit replaces the selection only if it is still exactly what was read. */
object SelectionGuard {
    /** The app may hand over a shortened selection; replacing it would delete the rest. */
    fun complete(selected: CharSequence?, selStart: Int, selEnd: Int): Boolean =
        selected != null && selEnd > selStart && selected.length == selEnd - selStart

    fun unchanged(startA: Int, endA: Int, startB: Int, endB: Int, genA: Int, genB: Int): Boolean =
        startA == startB && endA == endB && genA == genB
}
