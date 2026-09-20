// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAwakeTest {

    /**
     * 🔴 **The default is what the app did before this was a setting**, and it has to stay that
     * way: the screen going off mid-game ends the session (2026-09-16), and an upgrade must not
     * hand an existing user that failure because a default moved.
     */
    @Test
    fun `the default holds the screen awake`() {
        assertEquals(ScreenAwakeMode.ALWAYS, ScreenConfig().awakeMode)
        assertTrue(ScreenConfig().awakeMode.keepAwake(connected = true))
        assertTrue(ScreenConfig().awakeMode.keepAwake(connected = false))
    }

    /** 🔑 The whole point of this mode: it is the connection state that decides, nothing else. */
    @Test
    fun `while connected follows the connection`() {
        assertTrue(ScreenAwakeMode.WHILE_CONNECTED.keepAwake(connected = true))
        assertFalse(ScreenAwakeMode.WHILE_CONNECTED.keepAwake(connected = false))
    }

    /** Off is off even mid-session — that is what the label warns about. */
    @Test
    fun `off never holds the screen`() {
        assertFalse(ScreenAwakeMode.OFF.keepAwake(connected = true))
        assertFalse(ScreenAwakeMode.OFF.keepAwake(connected = false))
    }
}
