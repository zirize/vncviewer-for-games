// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

/**
 * Picks the **chroma resolution (JPEG subsampling)** from the measured load.
 *
 * 🔑 **Upstream's version (TigerVNC `vncviewer/CConn.cxx`) cannot simply be ported.** Looking at it:
 *   1. Upstream's `autoSelect` **does not touch subsampling** at all — it changes `qualityLevel`
 *      (8 vs 6) and the pixel format (palette switching). The axis we want is not there.
 *   2. Its threshold is **16Mbps** (`bpsEstimate > 16000000` → quality 8, else 6). We run at
 *      **671Mbps**, forty times higher, so upstream's rule would say quality 8 always — i.e. do
 *      nothing, ever.
 *   3. Its other threshold, 256kbps for palette switching, is further away still.
 *  ⇒ **The *shape* of the estimator is taken ([bps] EWMA below); the decision rule is new.**
 *
 * 🔑 **Why bandwidth alone cannot decide this** — the link ceiling differs by device and router
 *    and cannot be known in advance. The 2026-09-16 measurements show the trap:
 *      · mosaic 4:4:4 — 41MB/s, busy 0.76, **keeping up with the source completely**
 *      · noise  4:2:2 — 79MB/s, busy 0.97, **also keeping up completely**
 *    An absolute byte threshold would call mosaic "idle" and noise-at-4:2:2 "drowning". Both are wrong.
 *  ⇒ The question asked instead is **"is current throughput sitting near the highest this
 *    connection has ever seen"** — a relative measure. It learns the link ceiling itself, which is
 *    how you avoid baking a number measured on one phone into a constant.
 *
 * 🔴 **A decision needs both signals to agree.** Either one alone gets it wrong:
 *   · `busy` alone (the fraction of time spent inside an update) cannot tell a genuinely idle but
 *     busy-looking case like mosaic apart from a loaded one.
 *   · bytes alone read "not near the ceiling" as "plenty of room" on a light screen.
 *
 * 🚫 **Verified without a device** — this class is pure functions, with the clock passed in.
 *    → `AutoSubsamplingTest`.
 */
class AutoSubsampling(
    /**
     * Starting rung. Defaults to [LADDER].last(), the **cheapest** one (4:2:0).
     *
     * 🔑 Starting cheap is what makes "automatic is never worse than fixed 4:2:0" true. Starting at
     * the best colour instead was tried and took **7 seconds** to settle down on a heavy screen —
     * seven seconds attached to every connection. See the matching note in `VncEngine`.
     *
     * ⚠️ The Korean comment that used to be here said the engine starts at the *best* colour rung.
     * That was left behind by the 2026-09-16 change and contradicted this default; it was wrong,
     * not merely out of date.
     */
    startLevel: Int = LADDER.last(),
) {
    /** The rung currently chosen (`SUBSAMP_*` in [com.tigervnc.rfb.JpegCompressor]). */
    var level: Int = startLevel
        private set

    /** Why the last decision was made. Kept so it can be logged — 🔑 if you cannot see *why* it changed, you cannot fix it. */
    var lastReason: String = ""
        private set

    private var windowStartNs = 0L
    private var windowBytes = 0L
    private var windowBusyNs = 0L
    private var windowsSeen = 0
    /**
     * 🔴 Do not use `Long.MIN_VALUE` as the "never changed" marker: `nowNs - Long.MIN_VALUE`
     *    overflows Long and comes out negative, so the dwell check then blocks **forever**.
     *    A unit test caught this on 2026-09-16. On a device it would have looked like nothing more
     *    than "automatic doesn't work".
     */
    private var lastChangeNs: Long? = null

    /** The highest throughput seen on this connection (bit/s). This is the link ceiling, learned rather than assumed. */
    var bpsPeak: Double = 0.0
        private set

    /** An EWMA estimate in the same shape as upstream's (bit/s), updated once per window. */
    var bps: Double = 0.0
        private set

    /**
     * Called at the end of every framebufferUpdate.
     *
     * @param bytes bytes received for this update. 🔴 `InStream.pos()` is an int and wraps every
     *              2.1GB, so **the caller passes a difference computed with int subtraction** (a
     *              difference within one update is small enough to be safe).
     * @param durationNs how long was spent inside this update.
     * @param nowNs a monotonic clock.
     * @return the new rung if it **changed**, otherwise null.
     */
    fun onUpdate(bytes: Long, durationNs: Long, nowNs: Long): Int? {
        // 🔴 The call that *opens* a window contributes no bytes or time. Accumulating into a
        //    zero-length window makes the next window divide two updates' worth by one second, and
        //    busy exceeds 1.0 (1.98 was actually observed). A unit test caught it on 2026-09-16.
        if (windowStartNs == 0L) { windowStartNs = nowNs; return null }
        windowBytes += bytes
        windowBusyNs += durationNs

        val windowNs = nowNs - windowStartNs
        if (windowNs < WINDOW_MS * 1_000_000L) return null

        val busy = windowBusyNs.toDouble() / windowNs
        val windowBps = windowBytes.toDouble() * 8.0 * 1_000_000_000.0 / windowNs
        windowStartNs = nowNs; windowBytes = 0; windowBusyNs = 0; windowsSeen++

        // 🔑 Upstream's EWMA shape, except the first window is taken as-is - no fake 20Mbps
        //    starting value. Upstream starts there; our thresholds are relative, so a seed would
        //    only introduce bias.
        bps = if (bps == 0.0) windowBps else bps * (1 - EWMA) + windowBps * EWMA
        if (bps > bpsPeak) bpsPeak = bps

        // 🔴 No decisions before warm-up: in the first window bpsPeak equals bps, so it always looks saturated.
        if (windowsSeen < WARMUP_WINDOWS) return null
        lastChangeNs?.let { if (nowNs - it < DWELL_MS * 1_000_000L) return null }
        if (bpsPeak <= 0.0) return null

        val ratio = bps / bpsPeak
        val idx = LADDER.indexOf(level).let { if (it < 0) 0 else it }

        val want = when {
            busy > DOWN_BUSY && ratio >= DOWN_RATIO && idx < LADDER.lastIndex -> idx + 1
            busy < UP_BUSY && ratio < UP_RATIO && idx > 0 -> idx - 1
            else -> return null
        }

        lastReason = "busy=%.2f bps=%.0fMbit/s peak=%.0fMbit/s(%.0f%%)"
            .format(busy, bps / 1e6, bpsPeak / 1e6, ratio * 100)
        level = LADDER[want]
        lastChangeNs = nowNs
        return level
    }

    companion object {
        /**
         * The ladder, from **best colour to fewest bytes**.
         * 🔴 This is **not** numeric order (0 = 4:4:4, 2 = 4:2:2, 1 = 4:2:0). Do not infer the
         * ordering from the values.
         */
        val LADDER = listOf(0, 2, 1)   // SUBSAMP_NONE, SUBSAMP_422, SUBSAMP_420

        const val WINDOW_MS = 1000L
        /** 🔑 Gives a change time to take effect. Without it the level oscillates every window. */
        const val DWELL_MS = 3000L
        const val WARMUP_WINDOWS = 3
        const val EWMA = 0.3

        // 🔴 **The hysteresis lives on `RATIO`** (0.85 vs 0.60); `BUSY` is the same on both sides.
        //    Measured, mosaic's busy is **0.76**, so an up-threshold of 0.75 would just barely miss
        //    calling it idle - and that was the *most* idle case of all, keeping up with the source
        //    completely.
        //    ⇒ busy answers only "are we pinned inside updates by the link"; ratio does the
        //    fine-grained part.
        const val DOWN_BUSY = 0.90
        const val DOWN_RATIO = 0.85
        const val UP_BUSY = 0.90
        const val UP_RATIO = 0.60
    }
}
