// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answers, **by computation**, which settings only apply from the next connection.
 *
 * 🔑 Writing "applies from the next connection" in words does not work - people read it and still
 * believe it changed now. So the screen shows **the difference from the snapshot taken at connect
 * time**, with the reconnect button right there.
 */
class VncConnectionConfigTest {

    /**
     * 🔴 **The default is automatic.** Written as a bare number it could change without anyone
     * noticing, so it is pinned here.
     * 🔑 Automatic starts at the cheap end of [AutoSubsampling.LADDER] (4:2:0), which is what
     *    guarantees "automatic is never worse than fixed 4:2:0".
     */
    @Test fun `chroma defaults to automatic`() {
        assertEquals(VncConnectionConfig.SUBSAMP_ADAPTIVE, VncConnectionConfig().subsampling)
        assertEquals(1, AutoSubsampling.LADDER.last())   // the starting rung is 4:2:0
    }

    @Test fun `no change means nothing pending`() {
        val c = VncConnectionConfig()
        assertTrue(c.diffRequiringReconnect(c.copy()).isEmpty())
    }

    @Test fun `changing the target shows up as pending`() {
        val connected = VncConnectionConfig()
        val now = connected.copy(host = "10.0.0.2", port = 5901)
        assertEquals(listOf("host", "port"), now.diffRequiringReconnect(connected))
    }

    /** 🔴 viewOnly applies immediately, so it is never pending - listing it would make the badge lie. */
    @Test fun `view only is never pending`() {
        val connected = VncConnectionConfig()
        val now = connected.copy(viewOnly = true)
        assertTrue(now.diffRequiringReconnect(connected).isEmpty())
    }

    @Test fun `negotiated settings are detected`() {
        val connected = VncConnectionConfig()
        val now = connected.copy(
            compressLevel = 9, qualityLevel = -1,
            pixelFormat = VncPixelFormatPreset.RGB_565_16BPP, clipboard = true,
        )
        assertEquals(
            listOf("compressLevel", "qualityLevel", "pixelFormat", "clipboard"),
            now.diffRequiringReconnect(connected),
        )
    }

    @Test fun `a change in encoding order is detected too`() {
        val connected = VncConnectionConfig()
        val now = connected.copy(encodings = connected.encodings.reversed())
        assertEquals(listOf("encodings"), now.diffRequiringReconnect(connected))
    }

    /** 🔑 A callback is not a value: swapping a lambda must not ask the user to reconnect. */
    @Test fun `replacing the password callback is not pending`() {
        val connected = VncConnectionConfig()
        val now = connected.copy(onPasswordRequired = { "x" })
        assertTrue(now.diffRequiringReconnect(connected).isEmpty())
    }
}
