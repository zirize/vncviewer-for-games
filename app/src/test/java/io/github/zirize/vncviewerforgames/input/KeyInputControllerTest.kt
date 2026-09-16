// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger of keys that are down. 🔑 It is what makes releasing them possible at all.
 *
 * 🔴 Why it is needed: the pointer side has `releaseAll()` to let go of held buttons when the
 * connection drops or the screen goes away. The key side had nothing. `onKeyDown`/`onKeyUp` pair
 * up by themselves so it never bit, but the on-screen `CTRL` and `ALT` (held) and the D-pad break
 * that pairing. Slide a finger off a button and the key stays down on the server forever, with
 * nothing on screen to say why.
 *
 * 🔑 This ledger knows nothing about policy - latching (one-shot, lock) is a layer above it.
 */
class KeyInputControllerTest {

    private val A = 'a'.code
    private val B = 'b'.code

    private fun ctl() = KeyInputController()

    @Test fun `a new ledger is empty`() {
        assertTrue(ctl().pressed.isEmpty())
    }

    @Test fun `pressing sends down and records it`() {
        val c = ctl()
        assertEquals(listOf(KeyCommand(A, true)), c.press(A))
        assertEquals(setOf(A), c.pressed)
    }

    // ── Idempotent in *state*, not in traffic ───────────────────────
    // 🔴 Swallowing the send too would break auto-repeat. Holding a physical key makes Android
    //    raise onKeyDown repeatedly, and passing those straight through *is* how repeat works here.
    //    Measured 2026-09-15: one tap of A = inputSent 2, a long press = 3. The third is the repeat.

    @Test fun `pressing the same key twice records it once`() {
        val c = ctl()
        c.press(A); c.press(A)
        assertEquals(setOf(A), c.pressed)
    }

    @Test fun `pressing the same key twice still sends two downs`() {
        val c = ctl()
        c.press(A)
        assertEquals(listOf(KeyCommand(A, true)), c.press(A))
    }

    @Test fun `releasing a key that is not down sends nothing`() {
        val c = ctl()
        assertEquals(emptyList<KeyCommand>(), c.release(A))
        assertTrue(c.pressed.isEmpty())
    }

    @Test fun `releasing a held key sends up and removes it`() {
        val c = ctl()
        c.press(A)
        assertEquals(listOf(KeyCommand(A, false)), c.release(A))
        assertTrue(c.pressed.isEmpty())
    }

    @Test fun `a tap produces down and up and leaves the ledger empty`() {
        val c = ctl()
        assertEquals(listOf(KeyCommand(A, true), KeyCommand(A, false)), c.tap(A))
        assertTrue(c.pressed.isEmpty())
    }

    // ── Release order: non-modifiers first, modifiers last ──────────
    // 🔴 The other order leaves a window where CTRL has gone but the other keys are still down on
    //    the server as bare keys. In a game that one tick fires as a misinput.

    @Test fun `releasing everything puts the modifiers last`() {
        val c = ctl()
        c.press(VncKeySym.ControlL); c.press(A); c.press(VncKeySym.ShiftL); c.press(B)
        val syms = c.releaseAll().map { it.keySym }
        assertEquals(listOf(A, B, VncKeySym.ControlL, VncKeySym.ShiftL), syms)
    }

    @Test fun `release-all emits only ups and empties the ledger`() {
        val c = ctl()
        c.press(VncKeySym.ControlL); c.press(A)
        val cmds = c.releaseAll()
        assertTrue(cmds.all { !it.down })
        assertTrue(c.pressed.isEmpty())
    }

    @Test fun `release-all on an empty ledger emits nothing`() {
        assertEquals(emptyList<KeyCommand>(), ctl().releaseAll())
    }

    // ── Reset when the connection changes ───────────────────────────
    // 🔑 Presses from the old server are not carried into the new session, and nothing is sent -
    //    there is nowhere to send it.

    @Test fun `reset clears the ledger and sends nothing`() {
        val c = ctl()
        c.press(VncKeySym.ControlL); c.press(A)
        assertEquals(emptyList<KeyCommand>(), c.reset())
        assertTrue(c.pressed.isEmpty())
    }

    // ── Normalising modifiers right after connecting ────────────────
    // Xtigervnc releases them itself (measured). ❓ x11vnc was never measured.
    //    The cost is seven ups, once per connection; the failure it prevents cannot be fixed from
    //    inside the app ⇒ keep it as insurance.

    @Test fun `normalising sends an up for each of the seven modifiers`() {
        val cmds = ctl().normalizeModifiers()
        assertTrue(cmds.all { !it.down })
        assertEquals(
            listOf(
                VncKeySym.ControlL, VncKeySym.ControlR, VncKeySym.ShiftL, VncKeySym.ShiftR,
                VncKeySym.AltL, VncKeySym.AltR, VncKeySym.SuperL,
            ),
            cmds.map { it.keySym },
        )
    }

    @Test fun `normalising does not touch the ledger`() {
        val c = ctl()
        c.press(A)
        c.normalizeModifiers()
        assertEquals(setOf(A), c.pressed)
    }

    // ── Observable ──────────────────────────────────────────────────
    // 🔴 Without this the latching UI would keep its own copy, recreating on the key side the
    //    "two versions of the truth" problem the pointer side already avoids.

    @Test fun `subscribers are told when the pressed set changes`() {
        val c = ctl()
        val seen = mutableListOf<Set<Int>>()
        c.onPressedChanged = { seen.add(it.toSet()) }
        c.press(A)
        c.press(VncKeySym.ControlL)
        c.release(A)
        c.releaseAll()
        assertEquals(
            listOf(setOf(A), setOf(A, VncKeySym.ControlL), setOf(VncKeySym.ControlL), emptySet()),
            seen,
        )
    }

    @Test fun `subscribers are not called when nothing changed`() {
        val c = ctl()
        c.press(A)
        val seen = mutableListOf<Set<Int>>()
        c.onPressedChanged = { seen.add(it.toSet()) }
        c.press(A)          // already down - the set is unchanged
        c.release(B)        // not down - the set is unchanged
        assertTrue(seen.isEmpty())
    }

    /** 🔑 A live view would mutate under the UI that is holding it, so a copy is handed over. */
    @Test fun `the set a subscriber received is not affected by later changes`() {
        val c = ctl()
        var first: Set<Int>? = null
        c.onPressedChanged = { if (first == null) first = it }
        c.press(A)
        c.press(B)
        assertEquals(setOf(A), first)
    }
}
