// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings sheet's keyboard actually sends.
 *
 * 🔑 The point of keeping this in plain Kotlin: "does typing work" is answered here, on a laptop,
 * rather than by installing the app and squinting at a game.
 *
 * 🔴 **Control characters are written as `\u` escapes, never as the byte itself.** A raw NUL makes
 * git call the file binary, and `check-private-info.sh` greps with `-I`, which **skips binary
 * files** - so that file would quietly stop being checked for private information and for Korean.
 * The gate would still print a green tick. (Happened here on 2026-09-17.)
 */
class TextInputTest {

    @Test
    fun plainTextBecomesOneKeysymPerCharacter() {
        assertEquals(listOf('h'.code, 'i'.code), TextInput.toKeySyms("hi"))
    }

    /**
     * 🔴 The **opposite** of the on-screen button rule, and deliberately so. A button labelled `F`
     * has to send lowercase `f`, but text that says `F` means a capital F - the server is what
     * holds Shift for it.
     */
    @Test
    fun uppercaseStaysUppercase() {
        assertEquals(listOf('F'.code), TextInput.toKeySyms("F"))
    }

    @Test
    fun newlineAndTabBecomeTheirKeys() {
        assertEquals(listOf('a'.code, VncKeySym.Return, 'b'.code), TextInput.toKeySyms("a\nb"))
        assertEquals(listOf(VncKeySym.Tab), TextInput.toKeySyms("\t"))
    }

    /** 🔑 CRLF is one newline. Two Returns would put a blank line into a chat box. */
    @Test
    fun crlfIsOneReturn() {
        assertEquals(listOf(VncKeySym.Return), TextInput.toKeySyms("\r\n"))
        assertEquals(listOf(VncKeySym.Return), TextInput.toKeySyms("\r"))
    }

    /** There is no key that types a bell or a null, so nothing is sent for one. */
    @Test
    fun otherControlCharactersAreDropped() {
        assertEquals(listOf('a'.code), TextInput.toKeySyms("\u0000a\u0007\u007F"))
    }

    /**
     * 🔴 A char is not a character. An emoji is a surrogate **pair**, and mapping each half alone
     * produces two keysyms that are not any character at all.
     */
    @Test
    fun surrogatePairIsOneKeysym() {
        assertEquals(listOf(0x01000000 + 0x1F600), TextInput.toKeySyms("\uD83D\uDE00"))
    }

    /** Outside Latin-1 the X11 rule is 0x01000000 + code point. (The syllable is the test's subject.) */
    @Test
    fun nonLatin1UsesTheUnicodeRange() {
        assertEquals(listOf(0x01000000 + 0xAC00), TextInput.toKeySyms("\uAC00"))
    }

    /** 🔑 One send is one burst of down/up pairs that are never coalesced, so the length is capped. */
    @Test
    fun longTextIsCapped() {
        val out = TextInput.toKeySyms("a".repeat(TextInput.MAX_CHARS * 2))
        assertEquals(TextInput.MAX_CHARS, out.size)
        assertTrue(out.all { it == 'a'.code })
    }

    @Test
    fun appendReturnAddsOneAtTheEnd() {
        assertEquals(listOf('a'.code, VncKeySym.Return), TextInput.toKeySyms("a", appendReturn = true))
    }

    /** ❓ Assumption: "send + Enter" on a line that already ends in a newline means one Enter, not two. */
    @Test
    fun appendReturnDoesNotDoubleUp() {
        assertEquals(listOf('a'.code, VncKeySym.Return), TextInput.toKeySyms("a\n", appendReturn = true))
    }

    /** With an empty field it is the sheet's "Enter only" button. */
    @Test
    fun emptyTextWithAppendReturnIsJustEnter() {
        assertEquals(listOf(VncKeySym.Return), TextInput.toKeySyms("", appendReturn = true))
        assertEquals(emptyList<Int>(), TextInput.toKeySyms(""))
    }

    /** The cap counts keys, so a capped send can still be confirmed. */
    @Test
    fun theCapDoesNotSwallowTheTrailingReturn() {
        val out = TextInput.toKeySyms("a".repeat(TextInput.MAX_CHARS * 2), appendReturn = true)
        assertEquals(TextInput.MAX_CHARS + 1, out.size)
        assertEquals(VncKeySym.Return, out.last())
    }
}
