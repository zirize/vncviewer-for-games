// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modifier latching - the layer that lets one finger type `CTRL+C`.
 *
 * 🔴 Why it is a separate layer above [KeyInputController]: the ledger must not know about policy,
 * which is what keeps it stable when the latching rules change.
 *
 * The rules: tap → ONESHOT, double-tap → LOCK, tap out of LOCK → OFF, a ONESHOT consumed by
 *       another key clears **after** that key's down/up pair, and 🔑 if the same keysym arrives
 *       physically, the latch loses.
 */
class ModifierLatchControllerTest {

    private val CTRL = VncKeySym.ControlL
    private val SHIFT = VncKeySym.ShiftL
    private val C = 'c'.code

    /** 🔑 Latching is **off by default** (see the default test below); these tests turn it on. */
    private fun ctl(cfg: KeyConfig = KeyConfig(latchEnabled = true)):
        Pair<ModifierLatchController, KeyInputController> {
        val keys = KeyInputController()
        return ModifierLatchController(keys, cfg) to keys
    }

    // ── Hold-and-use: left hand on CTRL, right hand clicking ────────
    // 🔑 The rule: **if something happened while it was held, it was used like a physical key, so
    //    lifting releases it.** If nothing happened, it was a tap meant to arm it, so it stays.
    //    ⇒ The same button behaves sensibly one-handed (tap) and two-handed (hold).

    @Test fun `pressing sends down immediately and becomes HELD`() {
        val (m, keys) = ctl()
        assertEquals(listOf(KeyCommand(CTRL, true)), m.pressModifier(CTRL, 0))
        assertEquals(LatchState.HELD, m.stateOf(CTRL))
        assertEquals(setOf(CTRL), keys.pressed)
    }

    @Test fun `hold, click, lift - and it releases`() {
        val (m, keys) = ctl()
        m.pressModifier(CTRL, 0)
        m.onMouseClick()                                   // the right hand clicks
        assertEquals(listOf(KeyCommand(CTRL, false)), m.releaseModifier(CTRL, 100))
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
        assertTrue(keys.pressed.isEmpty())
    }

    @Test fun `hold, type a key, lift - and it releases`() {
        val (m, _) = ctl()
        m.pressModifier(CTRL, 0)
        m.tapKey(C)
        m.releaseModifier(CTRL, 100)
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    /** 🔴 While it is held, ONESHOT must not be consumed - you may need to press several times. */
    @Test fun `held, it stays down across several keys`() {
        val (m, keys) = ctl()
        m.pressModifier(CTRL, 0)
        m.tapKey(C)
        assertTrue("CTRL should still be down after the first key", CTRL in keys.pressed)
        m.tapKey(C)
        assertTrue(CTRL in keys.pressed)
        m.releaseModifier(CTRL, 200)
        assertTrue(keys.pressed.isEmpty())
    }

    @Test fun `lifting with nothing having happened leaves it armed as ONESHOT`() {
        val (m, keys) = ctl()
        m.pressModifier(CTRL, 0)
        assertEquals(emptyList<KeyCommand>(), m.releaseModifier(CTRL, 50))
        assertEquals(LatchState.ONESHOT, m.stateOf(CTRL))
        assertTrue(CTRL in keys.pressed)
    }

    // ── State machine ───────────────────────────────────────────────

    /**
     * 🔴 Here `CTRL` is not "the Ctrl of Ctrl+C", it is **a game key** - held or not *is* the
     * meaning. ⇒ By default it does not arm. It can be turned on in settings.
     */
    @Test fun `latching is off by default`() {
        assertEquals(false, KeyConfig().latchEnabled)
    }

    @Test fun `nothing is armed to begin with`() {
        val (m, _) = ctl()
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    @Test fun `one tap arms ONESHOT and sends down`() {
        val (m, keys) = ctl()
        assertEquals(listOf(KeyCommand(CTRL, true)), m.tapModifier(CTRL, 0))
        assertEquals(LatchState.ONESHOT, m.stateOf(CTRL))
        assertEquals(setOf(CTRL), keys.pressed)
    }

    @Test fun `two quick taps lock it, sending nothing extra`() {
        val (m, _) = ctl()
        m.tapModifier(CTRL, 0)
        assertEquals(emptyList<KeyCommand>(), m.tapModifier(CTRL, 200))
        assertEquals(LatchState.LOCK, m.stateOf(CTRL))
    }

    @Test fun `tapping out of LOCK releases it and sends up`() {
        val (m, keys) = ctl()
        m.tapModifier(CTRL, 0); m.tapModifier(CTRL, 200)
        assertEquals(listOf(KeyCommand(CTRL, false)), m.tapModifier(CTRL, 1000))
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
        assertTrue(keys.pressed.isEmpty())
    }

    /** ❓ Not specified anywhere; closed as an assumption - a slow second tap means cancel. */
    @Test fun `a slow second tap out of ONESHOT cancels it`() {
        val (m, _) = ctl()
        m.tapModifier(CTRL, 0)
        assertEquals(listOf(KeyCommand(CTRL, false)), m.tapModifier(CTRL, 5000))
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    @Test fun `the double-tap window comes from the config`() {
        val (m, _) = ctl(KeyConfig(doubleTapMs = 100))
        m.tapModifier(CTRL, 0)
        m.tapModifier(CTRL, 150)                      // past 100ms, so not a double tap
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    // ── Consumption ─────────────────────────────────────────────────

    /** 🔴 The ordering is the point: finish the key completely, *then* release the modifier. */
    @Test fun `ONESHOT is consumed by the next key and clears after it`() {
        val (m, _) = ctl()
        m.tapModifier(CTRL, 0)
        assertEquals(
            listOf(KeyCommand(C, true), KeyCommand(C, false), KeyCommand(CTRL, false)),
            m.tapKey(C),
        )
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    @Test fun `LOCK is not consumed by the next key`() {
        val (m, _) = ctl()
        m.tapModifier(CTRL, 0); m.tapModifier(CTRL, 200)
        assertEquals(listOf(KeyCommand(C, true), KeyCommand(C, false)), m.tapKey(C))
        assertEquals(LatchState.LOCK, m.stateOf(CTRL))
    }

    @Test fun `several armed ONESHOTs all clear together`() {
        val (m, _) = ctl()
        m.tapModifier(CTRL, 0); m.tapModifier(SHIFT, 10)
        val syms = m.tapKey(C).map { it.keySym }
        assertEquals(listOf(C, C, CTRL, SHIFT), syms)
    }

    /** ❓ Initial default: a mouse click does **not** consume it. Use LOCK for Ctrl+click. */
    @Test fun `a mouse click does not clear ONESHOT by default`() {
        val (m, _) = ctl()
        m.tapModifier(CTRL, 0)
        assertEquals(emptyList<KeyCommand>(), m.onMouseClick())
        assertEquals(LatchState.ONESHOT, m.stateOf(CTRL))
    }

    @Test fun `with the setting on, a mouse click does clear ONESHOT`() {
        val (m, _) = ctl(KeyConfig(latchEnabled = true, mouseClickConsumesOneshot = true))
        m.tapModifier(CTRL, 0)
        assertEquals(listOf(KeyCommand(CTRL, false)), m.onMouseClick())
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    // ── 🔑 The physical keyboard wins ───────────────────────────────

    @Test fun `the latch folds when the same key arrives physically`() {
        val (m, keys) = ctl()
        m.tapModifier(CTRL, 0)
        m.onPhysicalKey(CTRL, true)
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
        assertTrue("the physical key is down, so the ledger must still hold it", CTRL in keys.pressed)
    }

    @Test fun `once released physically it leaves the ledger too`() {
        val (m, keys) = ctl()
        m.tapModifier(CTRL, 0)
        m.onPhysicalKey(CTRL, true)
        m.onPhysicalKey(CTRL, false)
        assertTrue(keys.pressed.isEmpty())
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    @Test fun `an ordinary physical key consumes ONESHOT as well`() {
        val (m, _) = ctl()
        m.tapModifier(CTRL, 0)
        val cmds = m.onPhysicalKey(C, true) + m.onPhysicalKey(C, false)
        assertEquals(listOf(C, C, CTRL), cmds.map { it.keySym })
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    // ── Release and subscription ────────────────────────────────────

    @Test fun `releasing everything clears every latch`() {
        val (m, keys) = ctl()
        m.tapModifier(CTRL, 0); m.tapModifier(CTRL, 200)   // LOCK
        m.releaseAll()
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
        assertTrue(keys.pressed.isEmpty())
    }

    @Test fun `subscribers are told when latch state changes`() {
        val (m, _) = ctl()
        val seen = mutableListOf<Map<Int, LatchState>>()
        m.onLatchChanged = { seen.add(it) }
        m.tapModifier(CTRL, 0)          // press (HELD) then lift (ONESHOT)
        m.tapModifier(CTRL, 200)        // press (HELD) then lift (LOCK)
        m.tapModifier(CTRL, 1000)       // press (HELD) then lift (OFF)
        // 🔑 HELD is notified too, because the button has to light up the instant it is touched.
        assertEquals(
            listOf(
                mapOf(CTRL to LatchState.HELD), mapOf(CTRL to LatchState.ONESHOT),
                mapOf(CTRL to LatchState.HELD), mapOf(CTRL to LatchState.LOCK),
                mapOf(CTRL to LatchState.HELD), emptyMap(),
            ),
            seen,
        )
    }

    // ── With latching turned off ────────────────────────────────────

    /**
     * 🔑 With latching off it is down **only while held** - exactly like a physical button.
     * (It used to toggle on every tap. Once press and release became distinguishable, that turned
     *  out to be *less* natural: staying down after release is what latching means.)
     */
    @Test fun `with latching off it is down only while held`() {
        val (m, keys) = ctl(KeyConfig(latchEnabled = false))
        assertEquals(listOf(KeyCommand(CTRL, true)), m.pressModifier(CTRL, 0))
        assertTrue(CTRL in keys.pressed)
        assertEquals(listOf(KeyCommand(CTRL, false)), m.releaseModifier(CTRL, 10))
        assertTrue(keys.pressed.isEmpty())
        assertEquals(LatchState.OFF, m.stateOf(CTRL))
    }

    @Test fun `a non-modifier key never arms a latch`() {
        val (m, keys) = ctl()
        assertEquals(listOf(KeyCommand(C, true), KeyCommand(C, false)), m.tapModifier(C, 0))
        assertEquals(LatchState.OFF, m.stateOf(C))
        assertTrue(keys.pressed.isEmpty())
    }
}
