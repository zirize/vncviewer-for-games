// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames

import android.os.SystemClock
import android.util.Log
import io.github.zirize.vncviewerforgames.conn.AutoSubsampling
import io.github.zirize.vncviewerforgames.conn.VncConnectionConfig
import io.github.zirize.vncviewerforgames.conn.VncPixelFormatPreset
import com.tigervnc.network.TcpSocket
import com.tigervnc.rfb.CConnection
import com.tigervnc.rfb.CSecurity
import com.tigervnc.rfb.SecurityClient
import com.tigervnc.rfb.UserPasswdGetter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.tigervnc.rfb.Point as VncPoint

class VncEngine(
    val config: VncConnectionConfig,
    private val callback: VncEngineCallback
) {
    private val TAG = "VncEngine"
    private val WATCHDOG_SILENCE_MS = 5000L
    /** Only frames taking longer than this get committed mid-flight. Short frames are untouched (= no tearing). */
    private val PROGRESSIVE_AFTER_MS = 60L
    /** Interval between mid-frame commits. Too often and you only pay more upload cost. */
    private val PROGRESSIVE_EVERY_MS = 30L

    /** The adaptive chroma switcher. null means fixed (the setting is not on automatic). */
    private var auto: AutoSubsampling? = null

    private var engineThread: Thread? = null
    @Volatile private var isRunning = false
    private var connection: MyCConnection? = null
    /**
     * The thread that does nothing but send input.
     * 🔴 **Its priority is raised.** Measured 2026-09-16: with the device CPU saturated (24% idle)
     *    this thread at default priority (nice 0) saw `inSnd` grow from 2ms to **20ms**. The send
     *    itself is tens of bytes and therefore cheap — the delay is waiting for a turn.
     * 🔑 It gets −8, close to the UI thread (nice −10 as top-app). The decoders sit at the other
     *    end (19). ⇒ "drop frames, never delay input" is written once more, in thread priorities.
     */
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            r.run()
        }, "VncInputWrite")
    }
    private val refreshExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /**
     * The coalescing slot. 🔑 It does not accumulate — only the newest one survives.
     * 🔴 [queuedAt] is not squeezed into an Int: `uptimeMillis` exceeds Int after 25 days of
     *    uptime and goes negative, at which point the instrumentation quietly produces negative
     *    latencies and throws the whole sample away.
     */
    private class PendingMove(val x: Int, val y: Int, val mask: Int, val queuedAt: Long)
    private val pendingMove = java.util.concurrent.atomic.AtomicReference<PendingMove?>()
    private val moveTaskQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 🔴 Telling whether the buttons *changed* needs the previous value. That is what makes it safe to coalesce moves only. */
    @Volatile private var lastButtonMask = 0

    /** When the last framebufferUpdate arrived; what the watchdog measures silence against. */
    @Volatile private var lastUpdateAtMs = 0L

    fun start() {
        if (isRunning) return
        isRunning = true
        engineThread = Thread { runEngine() }
        engineThread?.start()
    }

    fun stop() {
        isRunning = false
        connection?.close()
        engineThread?.interrupt()
        writeExecutor.shutdownNow()
        refreshExecutor.shutdownNow()
    }

    // ── The send gate ───────────────────────────────────────────────
    // 🔑 View-only blocking happens in exactly one place.
    //    New input paths all pass through here, so none of them can leak.

    private fun canSendInput(): Boolean {
        if (config.viewOnly) return false
        val conn = connection ?: return false
        return conn.state() == CConnection.stateEnum.RFBSTATE_NORMAL
    }

    /**
     * 🔑 **Stale moves are dropped; only the newest coordinate is sent** (coalescing).
     *
     * Measured 2026-09-16: with the device CPU saturated, a single write took up to 21ms (`inW` —
     *    the message-loop thread gets descheduled while holding `synchronized(os)`), and the moves
     *    behind it queued up until `inSnd` reached 37ms.
     *    🔴 **A queued move is an already-stale coordinate.** Sending them all just replays old
     *    positions in order, so the lag the finger feels stays exactly as it was.
     *
     * ⇒ Consecutive moves with the **same** button state collapse into the last one. Events that
     *    change the buttons (press, release) are never dropped — lose one and a click disappears
     *    or a key stays down.
     * 🔑 A click command carries its own coordinates, so coalescing the moves in front of it
     *    cannot make the click land somewhere else.
     */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        if (!canSendInput()) return
        val conn = connection ?: return
        val queuedAt = SystemClock.uptimeMillis()

        // Button-changing events go out as-is, and the coalescing slot is cleared to keep the order.
        if (buttonMask != lastButtonMask) {
            lastButtonMask = buttonMask
            pendingMove.set(null)
            writeExecutor.execute { writePointer(conn, x, y, buttonMask, queuedAt) }
            return
        }

        // A pure move - overwrite the slot with the newest value.
        pendingMove.set(PendingMove(x, y, buttonMask, queuedAt))
        if (moveTaskQueued.compareAndSet(false, true)) {
            writeExecutor.execute {
                moveTaskQueued.set(false)
                val m = pendingMove.getAndSet(null) ?: return@execute
                writePointer(conn, m.x, m.y, m.mask, m.queuedAt)
            }
        }
    }

    private fun writePointer(conn: MyCConnection, x: Int, y: Int, mask: Int, queuedAt: Long) {
        val startedAt = SystemClock.uptimeMillis()
        try {
            conn.writer()?.writePointerEvent(VncPoint(x, y), mask)
            io.github.zirize.vncviewerforgames.perf.PerfStats.inputSent()
            io.github.zirize.vncviewerforgames.perf.PerfStats.inputWrite(
                startedAt - queuedAt, SystemClock.uptimeMillis() - startedAt)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send pointer event", e)
        }
    }

    fun sendKeyEvent(keysym: Int, down: Boolean) {
        if (!canSendInput()) return
        val conn = connection ?: return
        val queuedAt = SystemClock.uptimeMillis()
        writeExecutor.execute {
            val startedAt = SystemClock.uptimeMillis()
            try {
                conn.writer()?.writeKeyEvent(keysym, down)
                io.github.zirize.vncviewerforgames.perf.PerfStats.inputSent()
                io.github.zirize.vncviewerforgames.perf.PerfStats.inputWrite(
                    startedAt - queuedAt, SystemClock.uptimeMillis() - startedAt)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to send key event", e)
            }
        }
    }

    fun sendClientCutText(text: String) {
        if (!canSendInput()) return
        val conn = connection ?: return
        writeExecutor.execute {
            try {
                conn.writer()?.writeClientCutText(text, text.length)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to send clipboard text", e)
            }
        }
    }

    // ── Connecting ──────────────────────────────────────────────────

    private fun buildSecurityClient(): SecurityClient {
        val sec = SecurityClient()
        sec.ClearSecTypes()                       // drop the default ("None") and fill from the config
        for (t in config.securityTypes) sec.EnableSecType(t.id)

        // 🔑 The password supplier is a global static (TigerVNC's design), so it is refreshed right before connecting.
        CSecurity.upg = object : UserPasswdGetter {
            override fun getUserPasswd(secure: Boolean, user: StringBuffer?, password: StringBuffer?) {
                val pw = config.password ?: config.onPasswordRequired?.invoke()
                if (password != null && pw != null) {
                    password.setLength(0)
                    password.append(pw)
                }
            }
        }
        return sec
    }

    private fun applyNegotiation(conn: MyCConnection) {
        when (config.pixelFormat) {
            VncPixelFormatPreset.BGRX_8888_32BPP ->
                conn.setPF(com.tigervnc.rfb.PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0))
            VncPixelFormatPreset.RGB_565_16BPP ->
                conn.setPF(com.tigervnc.rfb.PixelFormat(16, 16, false, true, 31, 63, 31, 11, 5, 0))
            VncPixelFormatPreset.SERVER_NATIVE -> { /* leave the server's format alone */ }
        }
        val compress = BuildConfig.VNC_COMPRESS_LEVEL.let { if (it in 0..9) it else config.compressLevel }
        if (compress != config.compressLevel)
            Log.w(TAG, "⚠️ instrumentation: compressLevel pinned from ${config.compressLevel} to $compress")
        conn.setCompressLevel(compress)
        // 🔑 Instrumentation override (−1 leaves the configured value, i.e. shipping behaviour). See build.gradle.kts.
        val quality = BuildConfig.VNC_QUALITY_LEVEL.let { if (it in 0..9) it else config.qualityLevel }
        if (quality != config.qualityLevel)
            Log.w(TAG, "⚠️ instrumentation: qualityLevel pinned from ${config.qualityLevel} to $quality")
        conn.setQualityLevel(quality)
        val subsamp = BuildConfig.VNC_SUBSAMPLING.let { if (it >= -2) it else config.subsampling }
        if (subsamp != config.subsampling)
            Log.w(TAG, "⚠️ instrumentation: subsampling pinned from ${config.subsampling} to $subsamp")
        // 🔴 Adaptive starts at the **cheap** end (4:2:0), so that "automatic is never worse than
        //    fixed 4:2:0" holds. Changed when automatic became the default on 2026-09-16.
        //    🔑 Starting at 4:4:4 instead was tried: on a heavy screen it took **7 seconds** to
        //       settle down to 4:2:0 (measured 21:59:17 → :21 → :24 — warm-up plus two dwells),
        //       and those 7 seconds would be attached to **every** connection. Getting the colour
        //       slightly later is the better trade.
        if (subsamp == VncConnectionConfig.SUBSAMP_ADAPTIVE) {
            auto = AutoSubsampling(startLevel = AutoSubsampling.LADDER.last())
            conn.setSubsampling(AutoSubsampling.LADDER.last())
            Log.i(TAG, "chroma = automatic (chosen from the measured load)")
        } else {
            auto = null
            conn.setSubsampling(subsamp)
        }
        conn.setEncodingList(config.encodingIds())
        // 🔴 **We have to declare "I can draw the cursor myself".**
        //    `CConnection.updateEncodings()` only *requests* the cursor pseudo-encoding when
        //    `cfgCursorShape && server.supportsLocalCursor`. That second flag is not something the
        //    server sends — the TigerVNC viewer sets it on itself, and that line was missing from
        //    this port.
        //    2026-09-16: so even with `cursorShape=true` the request never went out, the server
        //    kept compositing the cursor into the framebuffer, and **the cursor waited for frames.**
        //    🔑 It was caught by an *absent* log line: setCursor was never called once.
        conn.server.supportsLocalCursor = config.cursorShape
        conn.setPseudoEncodingOptions(
            config.desktopResize, config.cursorShape, config.clipboard, config.continuousUpdates
        )
        Log.d(TAG, "negotiation: cursorShape=${config.cursorShape} " +
                "supportsLocalCursor=${conn.server.supportsLocalCursor} " +
                "enc=${config.encodings} compress=$compress " +
                "quality=$quality pf=${config.pixelFormat} shared=${config.shared} " +
                "viewOnly=${config.viewOnly}")
    }

    private fun runEngine() {
        callback.onConnectionStateChanged("Connecting to ${config.host}:${config.port}...")
        try {
            val sock = TcpSocket(config.host, config.port)
            callback.onConnectionStateChanged("Connected. Handshaking...")

            val secClient = buildSecurityClient()
            connection = MyCConnection(secClient)
            // 🔴 `shared` rides along in ClientInit, so it has to be set BEFORE initialiseProtocol.
            connection?.setShared(config.shared)
            connection?.setStreams(sock.inStream(), sock.outStream())
            connection?.initialiseProtocol()

            callback.onConnectionStateChanged("VNC Protocol Initialized")
            lastUpdateAtMs = System.currentTimeMillis()

            // 🔑 This is a watchdog, not the engine that drives frames.
            // Frames come from the incremental request in CConnection.framebufferUpdateEnd();
            // this only forces a full update when the stream has been silent for longer than
            // WATCHDOG_SILENCE_MS.
            refreshExecutor.scheduleWithFixedDelay({
                if (!isRunning) return@scheduleWithFixedDelay
                val conn = connection ?: return@scheduleWithFixedDelay
                if (conn.state() != CConnection.stateEnum.RFBSTATE_NORMAL) return@scheduleWithFixedDelay
                val silentMs = System.currentTimeMillis() - lastUpdateAtMs
                if (silentMs < WATCHDOG_SILENCE_MS) return@scheduleWithFixedDelay
                try {
                    Log.w(TAG, "watchdog: no update for ${silentMs}ms - forcing a full update")
                    conn.refreshFramebuffer()
                } catch (e: Throwable) {
                    Log.e(TAG, "Watchdog refresh failed", e)
                }
            }, 2, 2, TimeUnit.SECONDS)

            while (isRunning) {
                val conn = connection
                if (conn?.processMsg() == false) {
                    Thread.sleep(5)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VNC Error", e)
            if (e is com.tigervnc.rfb.AuthFailureException) {
                // 🔑 The same credentials give the same answer, so reconnecting is blocked.
                callback.onFatalFailure("authentication failed: ${e.message}")
            } else {
                callback.onConnectionStateChanged("Error: ${e.message}")
            }
        } finally {
            isRunning = false
            callback.onConnectionStateChanged("Disconnected")
        }
    }

    inner class MyCConnection(secClient: SecurityClient) : CConnection() {
        init {
            this.security = secClient
        }

        private var frameStartNs = 0L
        /** 🔑 Sampled where upstream samples it: bytes received for this update. It is an int and wraps, so only differences are used. */
        private var frameStartPos = 0
    /** When this frame last did a mid-frame commit. 0 means it has not yet. */
    private var progressiveAtNs = 0L
        private var pixelBuffer: CustomPixelBuffer? = null

        /**
         * 🔴 This used to be a **no-op**, which is why turning `cursorShape` on made the cursor
         *    disappear entirely and the settings screen marked it as a dangerous option.
         *    Left off, the server composites the cursor into the framebuffer, so **the cursor
         *    waits for a frame** — which is what "it responds, but it doesn't feel quite natural"
         *    under load turned out to be. The input goes out in 6ms; seeing the result takes one
         *    frame interval, 40–60ms under load.
         */
        override fun setCursor(width: Int, height: Int, hotspot: VncPoint?, data: ByteArray?) {
            callback.onCursorShapeChanged(
                width, height, hotspot?.x ?: 0, hotspot?.y ?: 0, data ?: ByteArray(0))
        }
        override fun clientRedirect(port: Int, host: String?, x509subject: String?) {}
        override fun setColourMapEntries(firstColour: Int, nColours: Int, rgbs: IntArray?) {}
        override fun bell() {}

        override fun authSuccess() {
            super.authSuccess()
            Log.d(TAG, "Auth Success")
        }

        override fun initDone() {
            super.initDone()
            applyNegotiation(this)

            val format = this.server.pf()
            val w = this.server.width()
            val h = this.server.height()
            Log.d(TAG, "Init Done: ${serverName} - ${w}x${h}")

            callback.onDesktopSizeChanged(w, h)
            val bitmap = callback.onRequestBitmap(w, h)
            val pb = CustomPixelBuffer(format, w, h, bitmap)
            pixelBuffer = pb
            setFramebuffer(pb)

            // 🔑 CConnection sets state_ to RFBSTATE_NORMAL *before* calling initDone() (see the
            //    assignment-then-call order in CConnection.java), so input sent from here does get
            //    through the send gate.
            callback.onConnectionReady()
        }

        override fun framebufferUpdateStart() {
            super.framebufferUpdateStart()
            frameStartNs = System.nanoTime()
            frameStartPos = getInStream()?.pos() ?: 0
            progressiveAtNs = 0L
        }

        /**
         * 🔑 **Only slow frames get committed mid-flight** (the call was: tearing is acceptable).
         *
         * Until now nothing reached the bitmap until an update *finished*, so one large update
         * froze the screen for its whole duration — measured on 2026-09-15, that was what the
         * stutter actually was (`worstGap ≈ worstDec`, 80–200ms).
         *
         * 🔴 Mid-frame commits happen **only past [PROGRESSIVE_AFTER_MS]**.
         *    ⇒ For ordinary frames (a few to a few tens of ms) **nothing changes at all, so there
         *    is no tearing.** The trade is made only on frames that were about to stall.
         * 🔑 And the tear is momentary: once the workers finish writing their rects, `markDirty`
         *    covers them again, so **the next commit overwrites it properly** — it heals itself.
         */
        override fun dataRect(r: com.tigervnc.rfb.Rect, encoding: Int) {
            super.dataRect(r, encoding)
            val now = System.nanoTime()
            if (now - frameStartNs < PROGRESSIVE_AFTER_MS * 1_000_000L) return
            if (progressiveAtNs != 0L && now - progressiveAtNs < PROGRESSIVE_EVERY_MS * 1_000_000L) return
            progressiveAtNs = now
            pixelBuffer?.commitFrame()
        }

        override fun framebufferUpdateEnd() {
            lastUpdateAtMs = System.currentTimeMillis()
            super.framebufferUpdateEnd()   // decoder.flush(), then request the next incremental update
            // 🔑 Upload to the Bitmap once, after every decoder has finished.
            pixelBuffer?.commitFrame()
            val endNs = System.nanoTime()
            io.github.zirize.vncviewerforgames.perf.PerfStats.frame(
                (endNs - frameStartNs) / 1_000_000,
                getInStream()?.pos()?.toLong() ?: 0L
            )
            // 🔑 Exactly where upstream (CConn::framebufferUpdateEnd) calls autoSelect.
            //    🔴 Only the difference is used: pos() is an int and wraps every 2.1GB (a
            //    difference within one update is safe).
            auto?.let { a ->
                val delta = ((getInStream()?.pos() ?: 0) - frameStartPos).toLong()
                a.onUpdate(if (delta < 0) 0L else delta, endNs - frameStartNs, endNs)?.let { lv ->
                    setSubsampling(lv)   // goes out with the next requestNewUpdate(); no reconnect needed
                    Log.i(TAG, "chroma auto -> ${com.tigervnc.rfb.JpegCompressor.subsamplingName(lv)}" +
                            " (${a.lastReason})")
                }
            }
            callback.onFrameUpdated()
        }

        override fun handleClipboardData(data: String?) {
            super.handleClipboardData(data)
            if (data != null) callback.onClipboardDataReceived(data)
        }
    }
}
