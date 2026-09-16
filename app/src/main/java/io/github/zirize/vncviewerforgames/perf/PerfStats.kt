// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.perf

import android.util.Log

/**
 * Per-stage performance counters, printed as one line per second.
 * 🔑 Logging per event on a hot path **changes what it measures**, so this only aggregates.
 */
object PerfStats {
    private const val TAG = "PERF"
    @Volatile var enabled = true

    private var windowStartMs = 0L
    private var frames = 0
    private var rects = 0
    private var pixels = 0L
    private var jpegRects = 0
    private var decodeMs = 0L
    private var renderMs = 0L
    private var renderCalls = 0
    private var convNs = 0L
    private var convPx = 0L
    private var setPixelsNs = 0L
    private var inputSent = 0
    // 🔑 The input **latency** axis, added after "under load the input stutters badly".
    //    🔴 `input=` only counts **how many** went out. A state where the count is perfectly
    //       healthy but every one of them is 200ms late is invisible on that axis forever - what a
    //       finger feels is latency, not throughput.
    //    So two separate figures:
    //      inLat  from when the MotionEvent was *created* to when onTouchEvent handles it
    //             = how far behind the UI thread is. 🔑 Drawing on the UI thread inflates this.
    //      inSnd  from "send this" to writeExecutor actually writing = send-queue delay.
    //    ⇒ Splitting them is what distinguishes "the UI is behind" from "the socket is blocked".
    private var inLatMs = 0L
    private var inLatN = 0
    private var maxInLatMs = 0L
    private var inSndMs = 0L
    private var inSndN = 0
    private var maxInSndMs = 0L
    // 🔑 A large inSnd has two possible causes - not getting scheduled, and the write itself
    //    taking time (lock, socket). Without splitting them you cannot tell whether to raise a
    //    thread priority or to fix a lock.
    private var inWrMs = 0L
    private var maxInWrMs = 0L
    private var commitPx = 0L
    private var commits = 0
    private var bytesAtStart = -1L
    // 🔴 `InStream.pos()` is an **int**, so it wraps negative every 2^31 bytes (2.1GB).
    //    This bit on 2026-09-16 with the stress clip (stripes/noise at 73MB/s): it wrapped every
    //    30 seconds, `bytesAtStart` went negative, the guard below rejected it, and **MB/s read
    //    0.00 for thirty seconds at a time**. The picture was arriving fine (Mpx/s = 56) with
    //    bandwidth showing zero - which makes the "is the network blocked" question unanswerable.
    //    ⇒ The wrap is undone here and accumulated in 64 bits. Upstream tigervnc is left alone.
    private var lastPosRaw = Long.MIN_VALUE
    private var bytesTotal = 0L
    private fun unwrap(posRaw: Long): Long {
        if (lastPosRaw != Long.MIN_VALUE) {
            var d = posRaw - lastPosRaw
            if (d < 0) d += 1L shl 32          // one wrap
            // 🔑 A reconnect resets pos to 0, which is indistinguishable from a wrap - so only plausible sizes are accepted.
            if (d in 0..(1L shl 28)) bytesTotal += d
        }
        lastPosRaw = posRaw
        return bytesTotal
    }
    private var workerNs = 0L
    private var workerJobs = 0
    private var maxPar = 0
    private var starved = 0
    // 🔑 **`decode` wall clock is 96% busy while only 1.15 workers are running.** What fills the
    //    gap had never been measured before 2026-09-16. Three stretches of the queue path run on
    //    the **main** thread, and all three are serial, so parallelism cannot shrink them:
    //      readNs   pulling rect bytes off TCP into a buffer (one thread, because order matters)
    //      waitNs   time spent waiting for a free buffer  <- large here means the workers cannot keep up
    //      affectNs getAffectedRegion - working out what changes
    //    🔑 If read dominates, no amount of decoder parallelism helps.
    private var readNs = 0L
    private var readBytes = 0L
    private var waitNs = 0L
    private var affectNs = 0L
    // 🔑 read is split further into select versus the read itself: upstream calls select once per
    //    read even when data is already waiting (two syscalls). Which half costs more had never
    //    been measured.
    private var selNs = 0L
    private var rdNs = 0L
    private var rdCalls = 0
    // 🔑 Stutter does not show up in an average: one 300ms freeze per second leaves average fps
    //    looking fine. So the **worst single event in the window** is tracked separately.
    // 🔴 **`maxGapMs` also grows when the screen is simply idle** - nothing changes, so no frames
    //    arrive. Measured 2026-09-15: a still screen gave worstGap 800ms while worstDec was 11-31ms.
    //    ⇒ **To read `worstGap` as stutter, read `worstDec` next to it.** Similar values mean real
    //       stutter; a large worstGap alone just means there was nothing to do.
    private var lastFrameAtMs = 0L
    private var maxGapMs = 0L
    private var maxDecodeMs = 0L
    private var maxRenderMs = 0L
    private val encRects = HashMap<Int, Int>()

    // 🔑 Rect **size** distribution - tells you whether adaptive decoding (small rects skip the
    //    queue) fires at all under a given load. An average cannot: a mean of 190x190 is consistent
    //    with half the rects being tiny. Buckets are by pixel **area**.
    //    [0]<=1K(32^2) [1]<=4K(64^2) [2]<=16K(128^2) [3]<=64K(256^2) [4] larger
    private val rectPxBuckets = IntArray(5)
    private fun bucketOf(px: Int) = when {
        px <= 1 shl 10 -> 0; px <= 1 shl 12 -> 1; px <= 1 shl 14 -> 2; px <= 1 shl 16 -> 3; else -> 4
    }

    // 🔑 Dispatch wall clock - from readRect finishing to decodeRect returning.
    //    Covers getAffectedRegion, the QueueEntry allocation, two lock acquisitions and signalAll.
    //    🔴 This is the ceiling on what adaptive decoding can save; nothing can do better.
    private var dispNs = 0L

    private fun encName(e: Int) = when (e) {
        0 -> "Raw"; 1 -> "CopyRect"; 2 -> "RRE"; 5 -> "Hextile"; 7 -> "Tight"; 16 -> "ZRLE"
        else -> "enc$e"
    }

    @Synchronized fun rect(encoding: Int, px: Int) {
        if (!enabled) return
        rects++; pixels += px
        rectPxBuckets[bucketOf(px)]++
        encRects[encoding] = (encRects[encoding] ?: 0) + 1
    }

    @Synchronized fun jpegRect() { if (enabled) jpegRects++ }

    /** How many input events **actually went out**. This is how view-only gets verified. */
    @Synchronized fun inputSent() { if (enabled) inputSent++ }

    /**
     * From when the input was **created** to when the UI thread handles it - the lag a finger feels.
     * 🔑 `MotionEvent.eventTime` and `SystemClock.uptimeMillis()` are the same clock.
     */
    fun inputDispatch(latMs: Long) {
        if (!enabled || latMs < 0) return
        // 🔴 Emitted outside the lock - see the [emit] comment below.
        val line = synchronized(this) {
            inLatMs += latMs; inLatN++
            if (latMs > maxInLatMs) maxInLatMs = latMs
            // 🔴 The report has to come out **while frames are stalled**, because the moment this
            //    axis matters is exactly the moment the screen freezes. Hang it off frame() alone
            //    and the log disappears precisely then.
            flushIfDue(System.currentTimeMillis(), bytesTotal)
        }
        emit(line)
    }

    /**
     * One send. [qMs] is time spent queued (from "send this" to the thread *starting*),
     * [wMs] is the write itself (`synchronized(os)` plus the socket write).
     */
    @Synchronized fun inputWrite(qMs: Long, wMs: Long) {
        if (!enabled || qMs < 0) return
        inSndMs += qMs; inSndN++
        if (qMs > maxInSndMs) maxInSndMs = qMs
        inWrMs += wMs
        if (wMs > maxInWrMs) maxInWrMs = wMs
    }

    /** The stretch that converts a rect into the internal framebuffer. */
    @Synchronized fun pixelConv(px: Int, convNsDelta: Long) {
        if (!enabled) return
        convPx += px; convNs += convNsDelta
    }

    /** The once-per-frame Bitmap upload. */
    @Synchronized fun commit(px: Int, nsDelta: Long) {
        if (!enabled) return
        setPixelsNs += nsDelta; commitPx += px; commits++
    }

    /**
     * How long a decoder worker actually spent on one rect, and how many workers were running at
     * that moment.
     * 🔑 Together these answer "are the four threads really parallel": if the worker/s sum never
     *    exceeds the decode wall clock, they are not. A [concurrency] maximum of 1 means fully serial.
     */
    @Synchronized fun worker(busyNs: Long, concurrency: Int) {
        if (!enabled) return
        workerNs += busyNs; workerJobs++
        if (concurrency > maxPar) maxPar = concurrency
    }

    /** Times a worker woke up and found nothing it could take **although the queue was not empty**. Direct evidence of a dispatch failure. */
    @Synchronized fun starve() { if (enabled) starved++ }

    /** Reading a rect off TCP into a buffer. 🔑 Serial - adding workers does not shrink it. */
    @Synchronized fun readRect(bytes: Int, ns: Long) {
        if (!enabled) return
        readNs += ns; readBytes += bytes
    }

    /** Time spent waiting for a free buffer. 🔑 Large here means the workers cannot keep up. */
    @Synchronized fun bufWait(ns: Long) { if (enabled) waitNs += ns }

    /** One socket read, counting the select wait and the read itself separately. */
    @Synchronized fun sockRead(selNsDelta: Long, rdNsDelta: Long) {
        if (!enabled) return
        selNs += selNsDelta; rdNs += rdNsDelta; rdCalls++
    }

    /** getAffectedRegion - working out what changed. Also serial, also on the main thread. */
    @Synchronized fun affected(ns: Long) { if (enabled) affectNs += ns }

    /** Time spent dispatching to the queue. [affected] is **included** in this - do not subtract it. */
    @Synchronized fun dispatch(ns: Long) { if (enabled) dispNs += ns }

    @Synchronized fun render(durMs: Long) {
        if (!enabled) return
        renderMs += durMs; renderCalls++
        if (durMs > maxRenderMs) maxRenderMs = durMs
    }

    /** Called at the end of each frame. bytesPos is the running total of bytes read. */
    fun frame(decodeDurMs: Long, bytesPos: Long) {
        if (!enabled) return
        emit(synchronized(this) { frameLocked(decodeDurMs, bytesPos) })
    }

    /**
     * 🔴 **The log line is written outside the lock.**
     * 2026-09-16: `Log.i` used to run inside `@Synchronized`. While that one line was being
     *    written, PerfStats' lock was held - and the first thing the input-send thread calls is
     *    `inputWrite()`, so **that wait was recorded as `inSnd`**.
     *    ⇒ What looked like "input is slow" was partly "the instrument is slow". This repository
     *      has been burned by exactly that before: a hot-path `Log.d` was half the stutter (2026-09-15).
     * 🔑 So the string is built inside the lock and printed outside it.
     */
    private fun emit(line: String?) { if (line != null) Log.i(TAG, line) }

    private fun frameLocked(decodeDurMs: Long, bytesPos: Long): String? {
        val now = System.currentTimeMillis()
        val bytesNow = unwrap(bytesPos)
        if (windowStartMs == 0L) { windowStartMs = now; bytesAtStart = bytesNow }
        frames++; decodeMs += decodeDurMs
        if (decodeDurMs > maxDecodeMs) maxDecodeMs = decodeDurMs
        // The gap BETWEEN frames - a spike here is exactly what the user perceives as stutter.
        if (lastFrameAtMs != 0L) {
            val gap = now - lastFrameAtMs
            if (gap > maxGapMs) maxGapMs = gap
        }
        lastFrameAtMs = now
        return flushIfDue(now, bytesNow)
    }

    /**
     * If the window has passed one second, print a line and open a new one.
     * 🔑 Called on **input** as well as on frames: the input axis has to stay alive while the
     *    screen is stalled. It used to live only inside frame(), so the log went quiet at exactly
     *    the moment worth watching.
     */
    private fun flushIfDue(now: Long, bytesNow: Long): String? {
        if (windowStartMs == 0L) { windowStartMs = now; bytesAtStart = bytesNow; return null }
        val elapsed = now - windowStartMs
        if (elapsed < 1000) return null

        val sec = elapsed / 1000.0
        val bytes = if (bytesAtStart >= 0) bytesNow - bytesAtStart else 0L
        val enc = encRects.entries.sortedByDescending { it.value }
            .joinToString(",") { "${encName(it.key)}=${it.value}" }
        val line = String.format(
            "fps=%.1f rect/s=%.0f Mpx/s=%.2f MB/s=%.2f decode=%.0fms/s worstGap=%dms worstDec=%dms worstRnd=%dms worker=%.0fms/s(%d) maxPar=%d starve=%d read=%.0fms/s(%.1fMB) sel=%.0fms/s rd=%.0fms/s(%d) bufWait=%.0fms/s affect=%.0fms/s disp=%.0fms/s conv=%.0fms/s upload=%.0fms/s(%d) convMpx/s=%.2f render=%.0fms/s(%d) jpegRect=%d input=%d inLat=%.0f/%dms(%d) inSnd=%.0f/%dms inW=%.0f/%dms rectPx=%s [%s]",
            frames / sec, rects / sec, pixels / sec / 1e6, bytes / sec / 1e6,
            decodeMs / sec, maxGapMs, maxDecodeMs, maxRenderMs,
            workerNs / 1e6 / sec, workerJobs, maxPar, starved,
            readNs / 1e6 / sec, readBytes / 1e6 / sec, selNs / 1e6 / sec, rdNs / 1e6 / sec, rdCalls, waitNs / 1e6 / sec, affectNs / 1e6 / sec, dispNs / 1e6 / sec,
            convNs / 1e6 / sec, setPixelsNs / 1e6 / sec, commits, convPx / sec / 1e6,
            renderMs / sec, renderCalls, jpegRects, inputSent,
            if (inLatN > 0) inLatMs.toDouble() / inLatN else 0.0, maxInLatMs, inLatN,
            if (inSndN > 0) inSndMs.toDouble() / inSndN else 0.0, maxInSndMs,
            if (inSndN > 0) inWrMs.toDouble() / inSndN else 0.0, maxInWrMs,
            rectPxBuckets.joinToString("/"), enc)

        windowStartMs = now; bytesAtStart = bytesNow
        frames = 0; rects = 0; pixels = 0; jpegRects = 0
        convNs = 0; convPx = 0; setPixelsNs = 0; inputSent = 0; commitPx = 0; commits = 0
        decodeMs = 0; renderMs = 0; renderCalls = 0; encRects.clear()
        workerNs = 0; workerJobs = 0; maxPar = 0; starved = 0
        readNs = 0; readBytes = 0; waitNs = 0; affectNs = 0; dispNs = 0
        rectPxBuckets.fill(0)
        selNs = 0; rdNs = 0; rdCalls = 0
        maxGapMs = 0; maxDecodeMs = 0; maxRenderMs = 0
        inLatMs = 0; inLatN = 0; maxInLatMs = 0
        inSndMs = 0; inSndN = 0; maxInSndMs = 0
        inWrMs = 0; maxInWrMs = 0
        return line
    }
}
