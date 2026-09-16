// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the adaptive chroma decision without a device.
 *
 * 🔑 The class has one question to answer: **is the link full right now?**
 * 🔴 Answering it from absolute byte counts is wrong. The 2026-09-16 measurements show the trap:
 *    · mosaic 4:4:4 at 41MB/s is **keeping up with the source completely** (idle)
 *    · noise  4:2:2 at 79MB/s is **also keeping up completely**
 *    So the test is **both** "is throughput near the highest this connection has seen" (relative)
 *    **and** "are we pinned inside updates" (busy).
 *
 * 🔑 **Not changing after a single window is correct** - the EWMA needs time to converge, which is
 *    why these tests feed the same load over several windows ([feed]).
 */
class AutoSubsamplingTest {

    private val sec = 1_000_000_000L

    /** Feeds load window by window and returns **the first decision**, or null if none is made. */
    private fun AutoSubsampling.feed(
        busy: Double, mbPerSec: Double, windows: Int, from: Long,
    ): Pair<Int?, Long> {
        var t = from
        repeat(windows) {
            t += sec
            onUpdate((mbPerSec * 1e6).toLong(), (busy * sec).toLong(), t)?.let { return it to t }
        }
        return null to t
    }

    /** 🔴 The ladder is not in numeric order: 0 (4:4:4) → 2 (4:2:2) → 1 (4:2:0). */
    @Test fun `the ladder runs from best colour to fewest bytes`() {
        assertEquals(listOf(0, 2, 1), AutoSubsampling.LADDER)
    }

    /** In the first window bpsPeak equals bps, so it always looks saturated. No decisions until warm. */
    @Test fun `no decision is made before warm-up`() {
        val a = AutoSubsampling(startLevel = 0)
        var t = 0L
        repeat(AutoSubsampling.WARMUP_WINDOWS) {
            t += sec
            assertNull(a.onUpdate((84 * 1e6).toLong(), (0.99 * sec).toLong(), t))
        }
    }

    @Test fun `when the link is full it gives up colour one rung at a time`() {
        val a = AutoSubsampling(startLevel = 0)
        val (first, t1) = a.feed(0.99, 84.0, 10, 0L)
        assertEquals(2, first)                       // 4:4:4 → 4:2:2

        // 🔑 The very next window is blocked by dwell, giving the change time to take effect.
        assertNull(a.onUpdate((84 * 1e6).toLong(), (0.99 * sec).toLong(), t1 + sec))

        val (second, t2) = a.feed(0.99, 84.0, 10, t1 + AutoSubsampling.DWELL_MS * 1_000_000L)
        assertEquals(1, second)                      // 4:2:2 → 4:2:0

        // At the bottom rung there is nowhere further to go
        val (third, _) = a.feed(0.99, 84.0, 10, t2 + AutoSubsampling.DWELL_MS * 1_000_000L)
        assertNull(third)
    }

    @Test fun `when things go idle it gives the colour back`() {
        val a = AutoSubsampling(startLevel = 1)
        // First let it learn this link's ceiling (at the bottom rung, so no decision comes out)
        val (none, t) = a.feed(0.99, 84.0, 6, 0L)
        assertNull(none)

        val (up, _) = a.feed(0.4, 20.0, 15, t)
        assertEquals(2, up)                          // 4:2:0 → 4:2:2
    }

    /** 🔴 Nothing happens in the band between the thresholds; without it, it oscillates at the edge. */
    @Test fun `in the ambiguous band it leaves the level alone`() {
        val a = AutoSubsampling(startLevel = 2)
        // busy is below the down threshold (0.90) and above the up one, so neither fires.
        // (Bytes are always at the maximum, so ratio is 1.0 - and it still will not go down
        //  without busy.)
        val (r, _) = a.feed(0.80, 84.0, 15, 0L)
        assertNull(r)
    }

    /**
     * 🔴 **This is the case an absolute byte threshold would have got wrong.**
     * mosaic receives *less* - 41MB/s - and still keeps up with the source completely, so the
     * correct reading is "idle".
     */
    @Test fun `a light screen reads as idle even though it receives fewer bytes`() {
        val a = AutoSubsampling(startLevel = 1)
        val (_, t) = a.feed(0.98, 84.0, 6, 0L)       // learn the peak from a heavy case first
        val (up, _) = a.feed(0.76, 41.0, 15, t)
        assertEquals(2, up)
    }
}
