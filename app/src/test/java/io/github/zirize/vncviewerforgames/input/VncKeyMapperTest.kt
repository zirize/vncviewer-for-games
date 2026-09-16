// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

import org.junit.Assert.assertEquals
import org.junit.Test

class VncKeyMapperTest {

    @Test
    fun printableAsciiMapsToItself() {
        assertEquals('a'.code, VncKeyMapper.charToKeySym('a'))
        assertEquals('Z'.code, VncKeyMapper.charToKeySym('Z'))
        assertEquals(0x20, VncKeyMapper.charToKeySym(' '))
    }

    @Test
    fun latin1MapsDirectly() {
        assertEquals(0xE9, VncKeyMapper.charToKeySym('é'))   // é
    }

    @Test
    fun nonLatin1UsesUnicodeKeysymRange() {
        // The X11 rule for anything outside Latin-1: 0x01000000 + code point.
        // 🔑 The Korean syllable below is the test's subject matter, not a leftover translation:
        //    it is here precisely because it is not Latin-1.
        assertEquals(0x01000000 + 0xAC00, VncKeyMapper.charToKeySym('\uAC00'))
    }

    @Test
    fun specialKeysMapByKeyCode() {
        assertEquals(VncKeySym.Return, VncKeyMapper.toKeySym(AndroidKey.ENTER, 0))
        assertEquals(VncKeySym.Escape, VncKeyMapper.toKeySym(AndroidKey.ESCAPE, 0))
        assertEquals(VncKeySym.BackSpace, VncKeyMapper.toKeySym(AndroidKey.DEL, 0))
        assertEquals(VncKeySym.Tab, VncKeyMapper.toKeySym(AndroidKey.TAB, 0))
        assertEquals(VncKeySym.Left, VncKeyMapper.toKeySym(AndroidKey.DPAD_LEFT, 0))
        assertEquals(VncKeySym.F5, VncKeyMapper.toKeySym(AndroidKey.F5, 0))
    }

    @Test
    fun modifierKeysMapToTheirOwnKeysyms() {
        assertEquals(VncKeySym.ControlL, VncKeyMapper.toKeySym(AndroidKey.CTRL_LEFT, 0))
        assertEquals(VncKeySym.ShiftL, VncKeyMapper.toKeySym(AndroidKey.SHIFT_LEFT, 0))
        assertEquals(VncKeySym.AltL, VncKeyMapper.toKeySym(AndroidKey.ALT_LEFT, 0))
    }

    /** 🔑 Anything not in the special-key table falls through as a Unicode character, which is how most typing arrives. */
    @Test
    fun unmappedKeyCodeFallsBackToUnicodeChar() {
        assertEquals('k'.code, VncKeyMapper.toKeySym(39 /* KEYCODE_K */, 'k'.code))
    }

    @Test
    fun noMappingAndNoCharYieldsZero() {
        assertEquals(0, VncKeyMapper.toKeySym(9999, 0))
    }

    /** A string to a list of keysyms, used by "send text". */
    @Test
    fun textBecomesKeysymSequence() {
        assertEquals(listOf('h'.code, 'i'.code), VncKeyMapper.textToKeySyms("hi"))
        assertEquals(listOf(VncKeySym.Return), VncKeyMapper.textToKeySyms("\n"))
    }

    /**
     * 🔴 Walked by **code point**, not by char: an emoji is a surrogate pair, and one keysym per
     * half is two keysyms that are not any character at all.
     */
    @Test
    fun textWalksCodePointsNotChars() {
        assertEquals(listOf(0x01000000 + 0x1F600), VncKeyMapper.textToKeySyms("😀"))
        assertEquals(0x01000000 + 0x1F600, VncKeyMapper.codePointToKeySym(0x1F600))
    }
}
