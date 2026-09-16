// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

/**
 * Typed text to the keysyms that type it on the server.
 *
 * 🔑 **Why typing needs its own layer.** The on-screen panel can only hold the keys a *game* needs.
 * Everything else — a save name, a chat line, a password box on the remote desktop — is a sentence,
 * and a sentence is not a button. This is the one place that turns one into key presses, so the
 * settings sheet has no rules of its own.
 *
 * 🔴 **It sends keys, not the clipboard.** A paste needs the remote side to cooperate (and the
 * extended clipboard is the option that has hung x11vnc-family servers before); a key press works
 * anywhere a keyboard works, including in a game that never heard of Ctrl+V.
 *
 * 🔑 Plain Kotlin with no Android imports, so it is tested under JUnit with no device.
 */
object TextInput {

    /**
     * 🔴 **A cap, because one send is one burst.** Every character is a down/up pair that is never
     * coalesced (dropping one would lose a keystroke), so a pasted wall of text becomes thousands
     * of messages queued ahead of the next touch. A thousand characters is far more than anything
     * anyone types into a game, and it keeps the worst case bounded.
     */
    const val MAX_CHARS = 1000

    /**
     * The keysyms for [text], in order.
     *
     * - **Code points, not chars.** An emoji is a surrogate *pair* in a Kotlin string; mapping each
     *   half on its own produces two keysyms that are not any character at all.
     * - Newlines become `Return` (`\r\n` is one, not two) and tabs become `Tab`.
     * - Other control characters are dropped — there is no key that types them.
     * - Uppercase letters stay uppercase. 🔑 This is the **opposite** of the button rule: a button
     *   labelled `F` must send lowercase `f`, but text that says `F` means the shifted one, and the
     *   server is what applies the Shift.
     *
     * [appendReturn] adds a final `Return` — "send and confirm". It does not add a second one when
     * the text already ends in a newline.
     */
    fun toKeySyms(text: String, appendReturn: Boolean = false): List<Int> {
        val out = ArrayList<Int>(minOf(text.length, MAX_CHARS) + 1)
        var i = 0
        while (i < text.length && out.size < MAX_CHARS) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\r'.code -> {
                    if (i < text.length && text[i] == '\n') i++   // CRLF is one Return
                    out += VncKeySym.Return
                }
                cp == '\n'.code -> out += VncKeySym.Return
                cp == '\t'.code -> out += VncKeySym.Tab
                cp < 0x20 || cp == 0x7F -> Unit                   // nothing types these
                else -> out += VncKeyMapper.codePointToKeySym(cp)
            }
        }
        if (appendReturn && out.lastOrNull() != VncKeySym.Return) out += VncKeySym.Return
        return out
    }
}
