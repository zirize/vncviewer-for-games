// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

/**
 * X11 keysym constants. An RFB KeyEvent carries these values verbatim.
 * 🔑 These are also the names the on-screen buttons and the profile JSON refer to.
 */
object VncKeySym {
    const val BackSpace = 0xFF08
    const val Tab       = 0xFF09
    const val Return    = 0xFF0D
    const val Escape    = 0xFF1B
    const val Home      = 0xFF50
    const val Left      = 0xFF51
    const val Up        = 0xFF52
    const val Right     = 0xFF53
    const val Down      = 0xFF54
    const val PageUp    = 0xFF55
    const val PageDown  = 0xFF56
    const val End       = 0xFF57
    const val Insert    = 0xFF63
    const val Menu      = 0xFF67
    const val NumLock   = 0xFF7F
    const val F1        = 0xFFBE
    const val F2        = 0xFFBF
    const val F3        = 0xFFC0
    const val F4        = 0xFFC1
    const val F5        = 0xFFC2
    const val F6        = 0xFFC3
    const val F7        = 0xFFC4
    const val F8        = 0xFFC5
    const val F9        = 0xFFC6
    const val F10       = 0xFFC7
    const val F11       = 0xFFC8
    const val F12       = 0xFFC9
    const val ShiftL    = 0xFFE1
    const val ShiftR    = 0xFFE2
    const val ControlL  = 0xFFE3
    const val ControlR  = 0xFFE4
    const val CapsLock  = 0xFFE5
    const val AltL      = 0xFFE9
    const val AltR      = 0xFFEA
    const val SuperL    = 0xFFEB
    const val Delete    = 0xFFFF
    const val Space     = 0x20

    /**
     * Name to keysym. **This is the spelling the profile JSON uses.**
     *
     * 🔑 **There is no second table.** The constants above *are* this table. Keep two and one of
     * them gets updated alone, producing "I wrote it in the profile but it does nothing".
     * 🔴 **Single characters are deliberately absent** — `"f"`, `"h"`, `"v"` are resolved by
     *    [VncKeyMapper.charToKeySym], because that is where upper and lower case are
     *    distinguished (a label is not a keysym).
     */
    private val byName: Map<String, Int> = mapOf(
        "BackSpace" to BackSpace, "Tab" to Tab, "Return" to Return, "Escape" to Escape,
        "Home" to Home, "Left" to Left, "Up" to Up, "Right" to Right, "Down" to Down,
        "PageUp" to PageUp, "PageDown" to PageDown, "End" to End, "Insert" to Insert,
        "Menu" to Menu, "NumLock" to NumLock,
        "F1" to F1, "F2" to F2, "F3" to F3, "F4" to F4, "F5" to F5, "F6" to F6,
        "F7" to F7, "F8" to F8, "F9" to F9, "F10" to F10, "F11" to F11, "F12" to F12,
        "ShiftL" to ShiftL, "ShiftR" to ShiftR, "ControlL" to ControlL, "ControlR" to ControlR,
        "CapsLock" to CapsLock, "AltL" to AltL, "AltR" to AltR, "SuperL" to SuperL,
        "Delete" to Delete, "Space" to Space,
    )

    /** Is this a modifier? 🔑 `latch` only means anything on a modifier. */
    val MODIFIERS: Set<Int> = setOf(ControlL, ControlR, ShiftL, ShiftR, AltL, AltR, SuperL)

    /**
     * Resolves whatever spelling a profile used, or null.
     * Three forms are accepted: a constant name (`"PageUp"`), a single character (`"f"`), or a
     * number (`"0xFF55"` or `65365`).
     */
    fun resolve(token: String): Int? {
        val s = token.trim()
        if (s.isEmpty()) return null
        byName[s]?.let { return it }
        if (s.length == 1) return VncKeyMapper.charToKeySym(s[0])
        val radix = if (s.startsWith("0x") || s.startsWith("0X")) 16 else 10
        val digits = if (radix == 16) s.substring(2) else s
        return digits.toIntOrNull(radix)
    }
}

/**
 * Just the Android keyCode values we need.
 * 🔑 `android.view.KeyEvent` is deliberately not imported, so the mapper can be tested under JUnit
 *    with no device. (These are public API constants; they do not change.)
 */
object AndroidKey {
    const val ALT_LEFT = 57
    const val ALT_RIGHT = 58
    const val SHIFT_LEFT = 59
    const val SHIFT_RIGHT = 60
    const val TAB = 61
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67            // backspace
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112   // Delete
    const val CTRL_LEFT = 113
    const val CTRL_RIGHT = 114
    const val CAPS_LOCK = 115
    const val META_LEFT = 117
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val INSERT = 124
    const val MENU = 82
    const val F1 = 131
    const val F2 = 132
    const val F3 = 133
    const val F4 = 134
    const val F5 = 135
    const val F6 = 136
    const val F7 = 137
    const val F8 = 138
    const val F9 = 139
    const val F10 = 140
    const val F11 = 141
    const val F12 = 142
}

/** Maps Android key input to X11 keysyms. Plain Kotlin, so it tests without a device. */
object VncKeyMapper {

    private val SPECIAL: Map<Int, Int> = mapOf(
        AndroidKey.ENTER to VncKeySym.Return,
        AndroidKey.DEL to VncKeySym.BackSpace,
        AndroidKey.FORWARD_DEL to VncKeySym.Delete,
        AndroidKey.TAB to VncKeySym.Tab,
        AndroidKey.ESCAPE to VncKeySym.Escape,
        AndroidKey.SPACE to VncKeySym.Space,
        AndroidKey.DPAD_UP to VncKeySym.Up,
        AndroidKey.DPAD_DOWN to VncKeySym.Down,
        AndroidKey.DPAD_LEFT to VncKeySym.Left,
        AndroidKey.DPAD_RIGHT to VncKeySym.Right,
        AndroidKey.PAGE_UP to VncKeySym.PageUp,
        AndroidKey.PAGE_DOWN to VncKeySym.PageDown,
        AndroidKey.MOVE_HOME to VncKeySym.Home,
        AndroidKey.MOVE_END to VncKeySym.End,
        AndroidKey.INSERT to VncKeySym.Insert,
        AndroidKey.MENU to VncKeySym.Menu,
        AndroidKey.SHIFT_LEFT to VncKeySym.ShiftL,
        AndroidKey.SHIFT_RIGHT to VncKeySym.ShiftR,
        AndroidKey.CTRL_LEFT to VncKeySym.ControlL,
        AndroidKey.CTRL_RIGHT to VncKeySym.ControlR,
        AndroidKey.ALT_LEFT to VncKeySym.AltL,
        AndroidKey.ALT_RIGHT to VncKeySym.AltR,
        AndroidKey.META_LEFT to VncKeySym.SuperL,
        AndroidKey.CAPS_LOCK to VncKeySym.CapsLock,
        AndroidKey.F1 to VncKeySym.F1, AndroidKey.F2 to VncKeySym.F2,
        AndroidKey.F3 to VncKeySym.F3, AndroidKey.F4 to VncKeySym.F4,
        AndroidKey.F5 to VncKeySym.F5, AndroidKey.F6 to VncKeySym.F6,
        AndroidKey.F7 to VncKeySym.F7, AndroidKey.F8 to VncKeySym.F8,
        AndroidKey.F9 to VncKeySym.F9, AndroidKey.F10 to VncKeySym.F10,
        AndroidKey.F11 to VncKeySym.F11, AndroidKey.F12 to VncKeySym.F12,
    )

    /**
     * One character to a keysym.
     * The X11 rule: Latin-1 (0x20-0xFF) maps to its own code point; anything else is
     * 0x01000000 + code point.
     */
    fun charToKeySym(c: Char): Int {
        val cp = c.code
        if (cp == '\n'.code || cp == '\r'.code) return VncKeySym.Return
        if (cp == '\t'.code) return VncKeySym.Tab
        return codePointToKeySym(cp)
    }

    /**
     * One **code point** to a keysym, by the same X11 rule.
     *
     * 🔴 A char is not a character. Anything above U+FFFF - an emoji, most of them - is a
     * surrogate *pair* in a Kotlin string, and mapping each half on its own yields two keysyms
     * that are not any character at all. Text goes through here; single keys still go through
     * [charToKeySym].
     */
    fun codePointToKeySym(cp: Int): Int {
        if (cp in 0x20..0xFF) return cp
        return 0x01000000 + cp
    }

    /**
     * Android keyCode (plus a Unicode character) to a keysym. 0 means there is nothing to send.
     * 🔑 The special-key table is consulted **first**: Enter and Tab also have Unicode values, so
     *    the other order would send them as control characters.
     */
    fun toKeySym(keyCode: Int, unicodeChar: Int): Int {
        SPECIAL[keyCode]?.let { return it }
        if (unicodeChar != 0) return charToKeySym(unicodeChar.toChar())
        return 0
    }

    /**
     * A string to a list of keysyms.
     * 🔑 Walks **code points**, so a surrogate pair stays one keysym. What a *user* types goes
     * through [TextInput] instead, which also drops control characters and caps the length.
     */
    fun textToKeySyms(text: String): List<Int> {
        val out = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            out += when (cp) {
                '\n'.code, '\r'.code -> VncKeySym.Return
                '\t'.code -> VncKeySym.Tab
                else -> codePointToKeySym(cp)
            }
        }
        return out
    }
}
