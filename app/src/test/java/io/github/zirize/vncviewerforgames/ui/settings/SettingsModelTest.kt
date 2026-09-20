// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.ui.settings

import io.github.zirize.vncviewerforgames.R
import io.github.zirize.vncviewerforgames.conn.VncConnectionConfig
import io.github.zirize.vncviewerforgames.display.ScreenAwakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {

    @Test
    fun `nothing is pending before the first connection`() {
        assertTrue(PendingChanges.of(VncConnectionConfig(), null).isEmpty)
    }

    @Test
    fun `no change means nothing pending, so the bar is not drawn`() {
        val c = VncConnectionConfig()
        assertTrue(PendingChanges.of(c, c.copy()).isEmpty)
    }

    @Test
    fun `changed settings are counted and have human names`() {
        val connected = VncConnectionConfig()
        val now = connected.copy(host = "192.0.2.10", qualityLevel = -1)
        val p = PendingChanges.of(now, connected)
        assertEquals(2, p.count)
        // 🔑 The wording itself lives in res/values/ and res/values-ko/, so this checks that a name
        //    exists at all rather than what it says - the test must not depend on a locale.
        assertEquals(R.string.label_host, SettingLabels.of("host"))
        assertEquals(R.string.label_quality_level, SettingLabels.of("qualityLevel"))
        for (key in p.keys) assertNotNull("no name for `$key`", SettingLabels.of(key))
    }

    @Test
    fun `view only applies immediately, so it never shows as pending`() {
        val connected = VncConnectionConfig()
        assertTrue(PendingChanges.of(connected.copy(viewOnly = true), connected).isEmpty)
    }

    /** 🔴 Shown as a bare number, −1 reads as "quality below 0". It has to have its own wording. */
    @Test
    fun `quality off gets its own label, not a number`() {
        assertEquals(R.string.quality_off, qualityLabelRes(-1))
        assertEquals(R.string.quality_high, qualityLabelRes(8))
        assertEquals(R.string.quality_low, qualityLabelRes(1))
        assertEquals("mid-range quality needs no wording", 0, qualityLabelRes(5))
    }

    /**
     * 2026-09-16: **three became two.** `CURSOR_SHAPE_ON` was dropped from the risky list — the
     * risk was "the server stops drawing the cursor, so the cursor disappears", and the app now
     * draws it itself, so it does not.
     * 🔑 Pinning the number here is what caught that change.
     */
    @Test
    fun `there are two risky options and both explain themselves`() {
        assertEquals(2, RiskyOption.entries.size)
        assertTrue(RiskyOption.entries.all { it.title != 0 && it.why != 0 })
    }

    /**
     * 🔴 Subsampling constants are 0 = 4:4:4, 1 = 4:2:0, 2 = 4:2:2, so numeric order and quality
     * order disagree. The choice list must be in **quality** order, largest bytes first.
     */
    @Test
    fun `the subsampling choice list is in quality order, not numeric order`() {
        assertEquals(listOf(-2, -1, 0, 2, 1, 3), SUBSAMPLING_CHOICES.map { it.first })
    }

    /**
     * 🔴 **A mode with no control is a state the user cannot get out of.** Add one to
     * `ScreenAwakeMode` without adding it here and the sheet simply has no button for it: the
     * saved value stays in force and nothing on screen says why the screen behaves as it does.
     * (The same rule `OverlayHitTest.hasSettingsEntry` enforces for the way into settings.)
     */
    @Test
    fun `every screen awake mode can be chosen and has wording`() {
        assertEquals(ScreenAwakeMode.entries.toList(), SCREEN_AWAKE_CHOICES.map { it.first })
        // 🔑 Most awake first, so left-to-right means "sleeps sooner".
        assertEquals(ScreenAwakeMode.ALWAYS, SCREEN_AWAKE_CHOICES.first().first)
        assertEquals(ScreenAwakeMode.OFF, SCREEN_AWAKE_CHOICES.last().first)
        for ((mode, short, _) in SCREEN_AWAKE_CHOICES) {
            assertTrue("no short label for ${'$'}mode", short != 0)
            assertTrue("no wording for ${'$'}mode", screenAwakeLabelRes(mode) != 0)
        }
    }
}
