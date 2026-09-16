// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the UI (the on-screen buttons) drives the mouse. `MWUP` and `MWDN` arrive here.
 *
 * 🔴 Why it goes through the controller rather than the engine: unlike keys, the mouse has shared
 * state - `cursorX/cursorY` accumulate from trackpad movement, and `heldMask` holds whatever
 * button is down. If the UI called the engine directly there would be two versions of the truth,
 * and the very next touch would snap the cursor back.
 *
 * 🔑 The wheel is a **button**: RFB has no wheel event, so one click is a down/up pair on a bit.
 */
class PointerUiApiTest {

    private fun ctl() = PointerInputController().apply { setBounds(1920, 1080) }

    @Test fun `one button tap produces a down and an up`() {
        val cmds = ctl().tapButton(VncButton.RIGHT)
        assertEquals(2, cmds.size)
        assertEquals(VncButton.RIGHT, cmds[0].buttonMask)
        assertEquals(VncButton.NONE, cmds[1].buttonMask)
    }

    @Test fun `a button tap happens where the cursor is`() {
        val c = ctl()
        val cmds = c.tapButton(VncButton.LEFT)
        assertTrue(cmds.all { it.x == c.cursorX && it.y == c.cursorY })
    }

    @Test fun `one wheel click is a down-up pair`() {
        val cmds = ctl().wheel(VncButton.WHEEL_UP, 1)
        assertEquals(listOf(VncButton.WHEEL_UP, VncButton.NONE), cmds.map { it.buttonMask })
    }

    @Test fun `three wheel clicks repeat the pair three times`() {
        val cmds = ctl().wheel(VncButton.WHEEL_DOWN, 3)
        assertEquals(6, cmds.size)
        assertEquals(
            listOf(VncButton.WHEEL_DOWN, VncButton.NONE, VncButton.WHEEL_DOWN,
                   VncButton.NONE, VncButton.WHEEL_DOWN, VncButton.NONE),
            cmds.map { it.buttonMask },
        )
    }

    @Test fun `a click count of zero or less produces nothing`() {
        val c = ctl()
        assertEquals(emptyList<PointerCommand>(), c.wheel(VncButton.WHEEL_UP, 0))
        assertEquals(emptyList<PointerCommand>(), c.wheel(VncButton.WHEEL_UP, -5))
    }

    /** 🔑 A cap, so rapid repeats cannot balloon the send queue. */
    @Test fun `the click count is capped at 20`() {
        assertEquals(40, ctl().wheel(VncButton.WHEEL_UP, 999).size)
    }

    /**
     * 🔴 Held buttons survive: pressing a UI button mid-drag must not end the drag.
     * A tap-and-a-half leaves the left button held, and then the wheel is turned.
     */
    @Test fun `a held button survives turning the wheel`() {
        val c = ctl()
        // tap, then press again immediately and drag (tap-and-a-half) to leave LEFT held
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 50))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 100))
        c.onEvent(TouchEvent(TouchAction.MOVE, 1, 140f, 140f, 150))
        val held = c.onEvent(TouchEvent(TouchAction.MOVE, 1, 180f, 180f, 200)).last().buttonMask
        assertEquals("tap-and-a-half should be holding LEFT", VncButton.LEFT, held)

        val cmds = c.wheel(VncButton.WHEEL_UP, 1)
        assertEquals(VncButton.LEFT or VncButton.WHEEL_UP, cmds[0].buttonMask)
        assertEquals(VncButton.LEFT, cmds[1].buttonMask)   // LEFT is still there afterwards
    }

    @Test fun `with no known screen size it produces nothing`() {
        val c = PointerInputController()   // setBounds was never called
        assertEquals(emptyList<PointerCommand>(), c.wheel(VncButton.WHEEL_UP, 1))
        assertEquals(emptyList<PointerCommand>(), c.tapButton(VncButton.RIGHT))
    }
}
