// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the overlay buttons sit, and which one a touch hits.
 *
 * 🔑 This is the last layer that can be settled without a device. How it *feels* under a real
 * finger needs a real hand, but "expanding to 48dp makes them overlap, and the nearer centre wins"
 * is **arithmetic, and arithmetic ends here.**
 */
class OverlayHitTestTest {

    private val MARGIN = 240f
    private val HEIGHT = 1080f
    /** 48dp at 420dpi (a scale of 2.625). */
    private val MIN_TOUCH = 48f * 2.625f

    /**
     * 🔑 The layout now lives in **JSON on disk**, so the test reads that same file.
     *    (A unit test's working directory is the module folder, so the repo root is one up.)
     */
    private val PROFILE = OverlayProfileParser.parse(File("../profiles/default.json").readText())

    private fun left() = layoutPanel(PROFILE, OverlayPanel.LEFT, MARGIN, HEIGHT)
    private fun right() = layoutPanel(PROFILE, OverlayPanel.RIGHT, MARGIN, HEIGHT)
    private fun byId(l: List<PlacedButton>, id: String) = l.first { it.button.id == id }

    // ── Panel width (changed from remote resolution to device screen on 2026-09-16) ──

    @Test fun `panel width is independent of the remote resolution - it comes from screen height`() {
        // 🔴 This is the property that closes "there are no on-screen buttons": whatever the
        //    remote resolution, the panel is still there.
        assertEquals(240f, panelWidthPx(1080f, PROFILE), 0.01f)
        assertEquals(320f, panelWidthPx(1440f, PROFILE), 0.01f)   // a taller screen grows the buttons too
    }

    @Test fun `on a 2400x1080 device the width is 240, the same as the old margin`() {
        // 🔑 It has to be the same picture as back when the remote was 1920 wide.
        val w = panelWidthPx(HEIGHT, PROFILE)
        assertEquals(MARGIN, w, 0.01f)
        val old = layoutPanel(PROFILE, OverlayPanel.LEFT, MARGIN, HEIGHT)
        val now = layoutPanel(PROFILE, OverlayPanel.LEFT, w, HEIGHT)
        old.zip(now).forEach { (a, b) ->
            assertEquals(a.left, b.left, 0.01f); assertEquals(a.top, b.top, 0.01f)
            assertEquals(a.width, b.width, 0.01f)
        }
    }

    @Test fun `overlap is 0 for a narrow remote and the whole panel for a full-width one`() {
        val w = panelWidthPx(HEIGHT, PROFILE)                      // 240
        // 1920 remote: the margin is 240, so nothing is covered - the old behaviour
        assertEquals(0f, panelOverlapPx(2400f, 1920f, w), 0.01f)
        // 2340 remote (the bench server): only 30 of margin, so 210 is covered
        assertEquals(210f, panelOverlapPx(2400f, 2340f, w), 0.01f)
        // 2400 remote (exactly the phone): no margin at all, so the whole panel sits on the picture
        assertEquals(240f, panelOverlapPx(2400f, 2400f, w), 0.01f)
        // Even a remote wider than the screen cannot cover more than the panel is wide
        assertEquals(240f, panelOverlapPx(2400f, 3840f, w), 0.01f)
    }

    // ── Placement ───────────────────────────────────────────────────

    @Test fun `the left panel's two columns fit exactly inside the 240 margin`() {
        val l = left()
        val up = byId(l, "pageup")
        val h = byId(l, "h")
        assertEquals(0f, up.left, 0.01f)
        assertEquals(114f, up.right, 0.01f)
        assertEquals(126f, h.left, 0.01f)
        assertEquals(240f, h.right, 0.01f)      // flush with the right edge
    }

    @Test fun `no button in the left panel spills out of the margin`() {
        // 🔴 Spilling out means sitting on top of the VNC picture.
        assertTrue(left().all { it.left >= -0.01f && it.right <= MARGIN + 0.01f })
    }

    @Test fun `the right panel is inset 41px from the outer edge`() {
        val v = byId(right(), "v")
        assertEquals(MARGIN - 41f, v.right, 0.01f)
    }

    @Test fun `the settings button is held 5px off each corner, for rounded screens`() {
        // From 2026-09-16: flush in the corner is hard to press on a rounded screen.
        // 🔴 y is measured from the bottom, so the button has moved *up* when its bottom sits
        //    5px above the bottom of the screen.
        val s = byId(left(), "settings")
        assertEquals(HEIGHT - 5f, s.bottom, 0.01f)
        assertEquals(5f, s.left, 0.01f)
    }

    @Test fun `a wider margin scales the whole panel by one factor`() {
        val wide = layoutPanel(PROFILE, OverlayPanel.LEFT, 480f, HEIGHT)
        val up = byId(wide, "pageup")
        assertEquals(228f, up.width, 0.01f)     // 114 × 2
        assertEquals(252f, byId(wide, "h").left, 0.01f)   // 126 × 2
    }

    // ── Hit testing ─────────────────────────────────────────────────

    @Test fun `pressing the middle of a button hits that button`() {
        val h = byId(left(), "h")
        assertEquals("h", hitTest(left(), h.centerX, h.centerY, MIN_TOUCH)?.button?.id)
    }

    @Test fun `somewhere with no button returns null`() {
        assertNull(hitTest(left(), 5f, 900f, MIN_TOUCH))
    }

    /** 🔴 48dp targets on 43.4dp buttons **must** overlap. This test pins that fact down. */
    @Test fun `the expanded touch area reaches outside the drawn button`() {
        val up = byId(left(), "pageup")
        // 5px past the right edge - outside what is drawn, inside what is touchable
        val hit = hitTest(left(), up.right + 5f, up.centerY, MIN_TOUCH)
        assertEquals("pageup", hit?.button?.id)
    }

    /** 🔑 In the overlapping band the nearer centre wins, splitting the band down the middle. */
    @Test fun `where they overlap, the nearer centre wins`() {
        val l = left()
        val up = byId(l, "pageup")      // left column
        val h = byId(l, "h")            // right column
        val y = (up.centerY + h.centerY) / 2f
        // a point leaning toward the left column's centre
        assertEquals("pageup", hitTest(l, up.centerX + 2f, up.centerY, MIN_TOUCH)?.button?.id)
        // a point leaning toward the right column's centre
        assertEquals("h", hitTest(l, h.centerX - 2f, h.centerY, MIN_TOUCH)?.button?.id)
        // exactly between the two - it must resolve to one of them, never both
        val mid = hitTest(l, (up.right + h.left) / 2f, up.centerY, MIN_TOUCH)
        assertNotNull(mid)
    }

    @Test fun `once the finger leaves the expanded area it is no longer that button`() {
        val up = byId(left(), "pageup")
        assertTrue(stillInside(up, up.centerX, up.centerY, MIN_TOUCH))
        assertTrue(!stillInside(up, up.centerX, up.bottom + 100f, MIN_TOUCH))
    }

    // ── D-Pad ────────────────────────────────────────────────────────

    @Test fun `the middle of the D-pad is no direction`() {
        val d = byId(left(), "dpad")
        assertNull(dpadDirection(d, d.centerX, d.centerY))
    }

    @Test fun `the D-pad resolves all four directions`() {
        val d = byId(left(), "dpad")
        assertEquals(DpadDirection.UP, dpadDirection(d, d.centerX, d.top + 5f))
        assertEquals(DpadDirection.DOWN, dpadDirection(d, d.centerX, d.bottom - 5f))
        assertEquals(DpadDirection.LEFT, dpadDirection(d, d.left + 5f, d.centerY))
        assertEquals(DpadDirection.RIGHT, dpadDirection(d, d.right - 5f, d.centerY))
    }

    /** 🔑 A diagonal still produces one direction: whichever axis is further from centre wins. */
    @Test fun `a diagonal on the D-pad produces exactly one direction`() {
        val d = byId(left(), "dpad")
        val dir = dpadDirection(d, d.right - 5f, d.top + 5f)
        assertTrue(dir == DpadDirection.RIGHT || dir == DpadDirection.UP)
    }

    // ── It must not lock itself ─────────────────────────────────────

    @Test fun `the default profile has a way into settings`() {
        assertTrue(hasSettingsEntry(PROFILE.buttons))
    }

    @Test fun `a profile with no settings button is rejected`() {
        val broken = PROFILE.buttons.filter { it.id != "settings" }
        assertTrue(!hasSettingsEntry(broken))
    }

    // ── A label is not a keysym ─────────────────────────────────────

    /**
     * 🔴 "The F key" means the value sent is **lowercase f** (0x66). In X11 an uppercase letter
     * is the key with Shift held - send that and **the screen looks fine while only the game
     * fails to respond.**
     */
    @Test fun `letter buttons send the lowercase keysym`() {
        listOf("f" to 'f', "h" to 'h', "v" to 'v').forEach { (id, ch) ->
            val b = PROFILE.buttons.first { it.id == id }
            assertEquals(ch.uppercaseChar().toString(), b.label)
            assertEquals(ch.code, (b.action as OverlayAction.Key).keySym)
        }
    }
}
