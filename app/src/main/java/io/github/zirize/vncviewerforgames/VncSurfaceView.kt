// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.util.Log
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.zirize.vncviewerforgames.input.KeyCommand
import io.github.zirize.vncviewerforgames.input.KeyConfig
import io.github.zirize.vncviewerforgames.input.KeyInputController
import io.github.zirize.vncviewerforgames.input.LatchState
import io.github.zirize.vncviewerforgames.input.ModifierLatchController
import io.github.zirize.vncviewerforgames.input.PointerCommand
import io.github.zirize.vncviewerforgames.input.PointerConfig
import io.github.zirize.vncviewerforgames.input.PointerInputController
import io.github.zirize.vncviewerforgames.input.PointerMode
import io.github.zirize.vncviewerforgames.input.TouchAction
import io.github.zirize.vncviewerforgames.input.TouchEvent
import io.github.zirize.vncviewerforgames.input.VncButton
import io.github.zirize.vncviewerforgames.input.VncKeyMapper
import android.view.KeyEvent
import io.github.zirize.vncviewerforgames.conn.VncConnectionConfig
import io.github.zirize.vncviewerforgames.conn.StartupFailureWatcher
import io.github.zirize.vncviewerforgames.conn.VncConnectionState

class VncSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, VncEngineCallback {

    private var vncWidth = 1920
    private var vncHeight = 1080
    private var vncBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var engine: VncEngine? = null
    private var reconnectScheduled = false
    /** After a failure that retrying cannot help, stop reconnecting automatically. */
    private var fatalFailure = false
    /** Passed up so the UI can tell the user (e.g. ask for the password again). */
    var onConnectionFatal: ((String) -> Unit)? = null

    /**
     * Called on every connection-state change.
     * 🔴 **If the UI does not draw this, the user cannot tell they have been disconnected** — the
     * last frame stays on screen, so "disconnected" and "the picture stopped moving" look
     * identical. Measured 2026-09-15: pointed at a dead port, it retried once a second and the
     * screen said nothing at all.
     */
    var onStateChanged: ((VncConnectionState) -> Unit)? = null

    private fun publishState(state: VncConnectionState) {
        connectionState = state
        // 🔑 Judged on every **emission**: consecutive failures arrive as the same value, so
        //    hanging this off `LaunchedEffect(state)` in the UI would stop firing after the first
        //    one (i.e. it would never open). The rules are in StartupFailureWatcher.
        val openSettings = startupFailure.onState(state)
        post {
            onStateChanged?.invoke(state)
            if (openSettings) {
                Log.i("VncSurfaceView", "failed from the start - opening settings " +
                        "(${startupFailure.failedAttempts} attempts, ${connectionConfig.host}:${connectionConfig.port})")
                releaseAllInput()          // 🔑 Let go of anything held before entering settings, same as opening it by hand
                onOpenSettings?.invoke()
            }
        }
    }

    /**
     * 🔴 **If the very first connection fails, settings opens itself.**
     * [StartupFailureWatcher] decides: never once a connection has succeeded, and at most once per
     * session.
     */
    var onOpenSettings: (() -> Unit)? = null
    private val startupFailure = StartupFailureWatcher()

    /** The current connection state. */
    var connectionState: VncConnectionState = VncConnectionState.Connecting("")
        private set

    /**
     * Connection, authentication and negotiation settings; the UI reads and writes them.
     * 🔑 Most only apply from the **next** connection (negotiation happens once, at connect time).
     * Only viewOnly takes effect immediately.
     */
    var connectionConfig: VncConnectionConfig = VncConnectionConfig()

    /**
     * View only. When true, not a single input event is sent. Applies immediately.
     *
     * 🔴 **Turning it on must release first, then block.** The send gate
     * (`VncEngine.canSendInput`) drops release commands just like any other, so switching it on
     * while a button or key is down leaves that press on the server **permanently** — toggling it
     * back off does not help, because the release still cannot get out, and there is then no way
     * to fix it from inside the app.
     * 🔑 The only reason this trap has never fired is that there is no view-only toggle in the UI.
     */
    var viewOnly: Boolean
        get() = connectionConfig.viewOnly
        set(value) {
            if (value && !connectionConfig.viewOnly) releaseAllInput()
            connectionConfig.viewOnly = value
        }

    private val pointer = PointerInputController()
    private val keys = KeyInputController()
    /** 🔑 Latching *policy* lives here, the *ledger* of what is down lives in keys. Splitting them keeps the ledger stable when the rules change. */
    private val latch = ModifierLatchController(keys)

    /** How key input feels. Mirrors [pointerConfig]. */
    var keyConfig: KeyConfig
        get() = latch.config
        set(value) { latch.config = value }

    /** The currently armed latches; what the on-screen modifier buttons colour themselves from. */
    val latchStates: Map<Int, LatchState> get() = latch.states

    /** Called when latch state changes. */
    var onLatchChanged: ((Map<Int, LatchState>) -> Unit)?
        get() = latch.onLatchChanged
        set(value) { latch.onLatchChanged = value }

    /**
     * A copy of the settings as they were **at connect time**. null means it has never connected.
     * 🔑 The settings screen compares "current" against this to draw a **pending** badge.
     */
    var connectedConfig: VncConnectionConfig? = null
        private set

    /**
     * Called when the remote screen size is established or changes.
     * 🔑 This is where the overlay gets the margin width from: margin = (view width − remote
     *    width) / 2. The bitmap is drawn 1:1 and centred, with no scaling — the same arithmetic
     *    `onTouchEvent` uses to correct coordinates.
     */
    var onDesktopSize: ((Int, Int) -> Unit)? = null

    /** Which "next connection" settings now differ from the ones in force. Empty means nothing is pending. */
    fun pendingConfigChanges(): List<String> =
        connectedConfig?.let { connectionConfig.diffRequiringReconnect(it) } ?: emptyList()
    private val longPressRunnable = Runnable { dispatchPointerTimeout() }

    // ── The settings API the UI uses ────────────────────────────────────
    /** Read and write. Setting a field directly takes effect at once. */
    var pointerConfig: PointerConfig
        get() = pointer.config
        set(value) { pointer.config = value }

    /** For a toggle button. Returns the mode it switched to. */
    fun togglePointerMode(): PointerMode {
        val next = if (pointer.config.mode == PointerMode.TRACKPAD) PointerMode.ABSOLUTE
                   else PointerMode.TRACKPAD
        pointer.config.mode = next
        return next
    }

    /** Flips the scroll direction and returns the new value (true = natural, the macOS way). */
    fun toggleScrollDirection(): Boolean {
        val next = !pointer.config.naturalScroll
        pointer.config.naturalScroll = next
        return next
    }

    /** Scroll direction. true = natural (content follows the finger), false = traditional. */
    var naturalScroll: Boolean
        get() = pointer.config.naturalScroll
        set(value) { pointer.config.naturalScroll = value }

    /**
     * Scroll sensitivity. Higher means less movement scrolls further.
     * Clamped to 0.25–8.0: at or below 0 scrolling runs away, and too small it does not move.
     */
    var scrollSensitivity: Float
        get() = pointer.config.scrollSensitivity
        set(value) { pointer.config.scrollSensitivity = value.coerceIn(0.25f, 8f) }

    /** Cursor movement sensitivity. Clamped to 0.25–5.0. */
    var pointerSensitivity: Float
        get() = pointer.config.sensitivity
        set(value) { pointer.config.sensitivity = value.coerceIn(0.25f, 5f) }

    val cursorX: Int get() = pointer.cursorX
    val cursorY: Int get() = pointer.cursorY

    /** Used when the screen needs to show a cursor. */
    var onCursorMoved: ((Int, Int) -> Unit)? = null

    /** Whether the server clipboard is mirrored into the Android clipboard. Off means callback only. */
    var clipboardToAndroid: Boolean = true

    /** Clipboard arrived from the server. For handling it yourself. */
    var onClipboardText: ((String) -> Unit)? = null
    /**
     * 🔑 **This handler is now for input timers only** (long press, reconnect scheduling).
     *    Drawing has moved off it — see [renderThread] below.
     */
    private val renderHandler = Handler(Looper.getMainLooper())

    // ── Render thread ───────────────────────────────────────────────────
    // 🔴 **Why drawing moved off the UI thread** (measured 2026-09-16):
    //    `onFrameUpdated()` was queueing draws onto the UI thread with `post { renderFrame() }`,
    //    and `renderFrame()` **blocks** twice over - `lockCanvas()` waits for vsync, and
    //    `synchronized(bmp)` shares a lock with commitFrame's setPixels. While those hold the UI
    //    thread, `onTouchEvent` simply queues up behind them.
    //    Evidence: on a quiet screen, `render=726ms/s` - 73% of the UI thread was drawing.
    //    Under load, worst input latency went from 27ms to **118ms**, a factor of 4.4.
    // The call was "tearing is fine, input lag is not" ⇒ frames get **dropped**.
    // 🔑 [framePending] is a flag, not a count: ten frames arriving mid-draw collapse into the one
    //    next draw. The queue cannot grow, so the latency cannot grow either.
    private val frameLock = java.lang.Object()
    @Volatile private var framePending = false
    @Volatile private var renderRunning = false
    private var renderThread: Thread? = null

    private fun startRenderThread() {
        if (renderThread != null) return
        renderRunning = true
        renderThread = Thread({
            try {
                while (renderRunning) {
                    synchronized(frameLock) {
                        // 🔑 The wait is capped at 500ms, doing the job the old renderTicker did:
                        //    insurance for a live surface that simply stops receiving frames.
                        while (renderRunning && !framePending) frameLock.wait(500)
                        framePending = false
                    }
                    if (!renderRunning) break
                    renderFrame()
                }
            } catch (e: InterruptedException) {
                // Asked to stop. Leave quietly.
            }
        }, "VncRender").also { it.start() }
    }

    private fun stopRenderThread() {
        renderRunning = false
        synchronized(frameLock) { frameLock.notifyAll() }
        // 🔴 This wait is mandatory: drawing after surfaceDestroyed has returned touches a dead Surface.
        renderThread?.join(1000)
        renderThread = null
    }

    // ── Local cursor ────────────────────────────────────────────────────
    // 🔴 **We draw the cursor ourselves.** A cursor composited into the framebuffer by the server
    //    has to wait for a frame, and under load frames are 40-60ms apart - so the cursor trails
    //    your finger by that much.
    //    That is what "it responds, but it doesn't feel quite natural" turned out to be. The input
    //    itself was already going out in 6ms; what was late was what you could **see**.
    // 🔑 So a cursor move calls [requestRender] regardless of frames.
    private class LocalCursor(val bmp: Bitmap, val hotX: Int, val hotY: Int)
    @Volatile private var localCursor: LocalCursor? = null

    override fun onCursorShapeChanged(
        width: Int, height: Int, hotspotX: Int, hotspotY: Int, argb: ByteArray
    ) {
        if (width <= 0 || height <= 0 || argb.size < width * height * 4) {
            localCursor = null                       // "hide the cursor"
            requestRender()
            return
        }
        // A,R,G,B bytes to a packed ARGB int. Cursors are small (usually <=32x32), so this is free.
        val px = IntArray(width * height)
        for (i in px.indices) {
            val o = i * 4
            px[i] = ((argb[o].toInt() and 0xFF) shl 24) or
                    ((argb[o + 1].toInt() and 0xFF) shl 16) or
                    ((argb[o + 2].toInt() and 0xFF) shl 8) or
                    (argb[o + 3].toInt() and 0xFF)
        }
        localCursor = LocalCursor(
            Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888), hotspotX, hotspotY)
        // 🔑 This arrives every time the shape changes, not just once. It is not a hot path, so
        //    it logs - and the **absence** of this line means the server is drawing the cursor
        //    instead, i.e. the cursor is waiting for frames again.
        Log.i("VncSurfaceView", "cursor: ${width}x${height} hot=($hotspotX,$hotspotY) - drawn by the app")
        requestRender()
    }

    /** Signals that there is something to draw. 🔑 It does not stack: an already-pending request absorbs it. */
    private fun requestRender() {
        synchronized(frameLock) { framePending = true; frameLock.notify() }
    }

    init {
        holder.addCallback(this)
        // 🔑 Focus is required to receive key events. Without it, onKeyDown is never called at all.
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ── Keyboard ────────────────────────────────────────────────────────
    // 🔑 Everything goes through the VncEngine gate, so viewOnly blocks it automatically.

    // 🔴 Every key send passes through the ledger (keys). No path calls the engine directly -
    //    leave one and you get a press the ledger does not know about, which is a key nobody can release.

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val sym = VncKeyMapper.toKeySym(keyCode, event.unicodeChar)
        if (sym == 0) return super.onKeyDown(keyCode, event)
        dispatchKeys(latch.onPhysicalKey(sym, true))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val sym = VncKeyMapper.toKeySym(keyCode, event.unicodeChar)
        if (sym == 0) return super.onKeyUp(keyCode, event)
        dispatchKeys(latch.onPhysicalKey(sym, false))
        return true
    }

    /**
     * Sends one keysym directly, for keys that must stay **held**. Constants are in `VncKeySym`.
     * 🚫 This **bypasses latching** - it is for things like the D-pad, held only while a finger is
     *    on the button. For buttons that arm on tap (on-screen CTRL, ALT) use [tapModifierKey].
     */
    fun sendKey(keySym: Int, down: Boolean) {
        dispatchKeys(if (down) keys.press(keySym) else keys.release(keySym))
    }

    /** Press and release, for one-shot keys like ESC, Enter and the arrows. Consumes any armed ONESHOT. */
    fun tapKey(keySym: Int) {
        dispatchKeys(latch.tapKey(keySym))
    }

    /**
     * A finger **landed** on an on-screen modifier button.
     * 🔑 It goes down immediately, which is what makes **left hand on CTRL, right hand clicking or
     * typing** work.
     */
    fun pressModifierKey(keySym: Int) {
        dispatchKeys(latch.pressModifier(keySym, SystemClock.uptimeMillis()))
    }

    /**
     * The finger **lifted**, which is where "does it stay armed" is decided: if anything happened
     * while it was held it was used **like a physical key**, so it releases; if nothing did it was
     * **a tap meant to arm it**, so it stays as `ONESHOT` (or `LOCK` on a fast second tap).
     */
    fun releaseModifierKey(keySym: Int) {
        dispatchKeys(latch.releaseModifier(keySym, SystemClock.uptimeMillis()))
    }

    /** Press and release. Read the resulting state from [latchStates]. */
    fun tapModifierKey(keySym: Int) {
        dispatchKeys(latch.tapModifier(keySym, SystemClock.uptimeMillis()))
    }

    /** Types a string one character at a time (non-Latin-1 goes out as Unicode keysyms). */
    fun sendText(text: String) {
        for (sym in VncKeyMapper.textToKeySyms(text)) tapKey(sym)
    }

    /** The keysyms currently down; what the on-screen CTRL button colours itself from. */
    val pressedKeys: Set<Int> get() = keys.pressed

    /** Fires when the pressed set changes. 🔑 Exposed so the UI never has to keep its own copy. */
    var onPressedKeysChanged: ((Set<Int>) -> Unit)?
        get() = keys.onPressedChanged
        set(value) { keys.onPressedChanged = value }

    // ── How the UI drives the mouse ─────────────────────────────────────
    // 🔴 Through PointerInputController, not the engine: the cursor position and heldMask have to
    //    live in exactly one place, or the next touch snaps the cursor back.

    /** Click one button, preserving anything already held. Constants are in `VncButton`. */
    fun tapMouseButton(button: Int) {
        dispatch(pointer.tapButton(button))
        dispatchKeys(latch.onMouseClick())
    }

    /** [clicks] wheel clicks up. 🔑 RFB has no wheel event: it is a down/up pair on a button bit. */
    fun sendWheelUp(clicks: Int = 1) = dispatch(pointer.wheel(VncButton.WHEEL_UP, clicks))

    /** [clicks] wheel clicks down. */
    fun sendWheelDown(clicks: Int = 1) = dispatch(pointer.wheel(VncButton.WHEEL_DOWN, clicks))

    private fun dispatchKeys(cmds: List<KeyCommand>) {
        val e = engine ?: return
        for (c in cmds) e.sendKeyEvent(c.keySym, c.down)
    }

    /**
     * Releases every held **key and button**.
     * 🔑 Keys first: releasing only the mouse button while a modifier is still down changes what
     *    the server sees for one tick. Ordering within the keys is [KeyInputController.releaseAll]'s job.
     */
    /**
     * Releases every held **key and button**.
     * 🔴 **Call this everywhere the overlay steps back** — opening settings, the screen going
     * away, the connection dropping, view-only being switched on. New places can just call this one
     * function.
     * 🔑 Opening settings especially: the bottom-left settings button shares a panel with the
     * `CTRL` latch button, so they are a finger's width apart. It is easy to walk into settings
     * with CTRL still armed, and then CTRL stays down in the game while the user is looking at a
     * settings sheet — **nothing on screen says so.**
     */
    fun releaseAllInput() {
        dispatchKeys(latch.releaseAll())
        dispatch(pointer.releaseAll())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("VncSurfaceView", "Surface created")
        // 🔑 Inside a Compose AndroidView, focus does not arrive by itself - and without focus
        //    onKeyDown is never called at all.
        requestFocus()
        startEngineIfNeeded()
        startRenderThread()
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("VncSurfaceView", "Surface changed: $width x $height")
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("VncSurfaceView", "Surface destroyed")
        // 🔴 Stop drawing and wait FIRST. The other order calls lockCanvas on a dead Surface.
        stopRenderThread()
        renderHandler.removeCallbacks(longPressRunnable)
        // 🔑 Release BEFORE engine.stop(): the socket has to still be alive for it to arrive.
        releaseAllInput()
        reconnectScheduled = false
        engine?.stop()
        engine = null
    }

    // --- VncEngineCallback ---

    override fun onConnectionStateChanged(state: String) {
        Log.d("VncSurfaceView", "State: $state")
        if (connectionState !is VncConnectionState.Failed) {
            publishState(
                if (state.startsWith("Error") || state == "Disconnected")
                    VncConnectionState.Retrying(lastErrorText(state))
                else VncConnectionState.Connecting(state)
            )
        }

        if (state.startsWith("Error") || state == "Disconnected") {
            post {
                // ⚠️ By this point the socket is already gone, so the releases never reach the
                //    server; only the local ledger clears. Remote release after an abnormal exit is
                //    impossible in principle - which is why normalisation right after connecting
                //    (onConnectionReady) exists as the insurance.
                releaseAllInput()
                scheduleReconnect()
            }
        }
    }

    override fun onConnectionReady() {
        publishState(VncConnectionState.Connected)
        post {
            // Do not carry presses from the old server into the new session (there is nowhere to send them).
            latch.reset()
            // 🔑 Snapshot the settings as of now; the settings screen draws "pending" by comparing against this.
            connectedConfig = connectionConfig.copy()
            // Insurance. Xtigervnc was measured to release a disconnected client's keys itself,
            //     but ❓ x11vnc - the actual target - was never measured. The cost is seven ups,
            //     once per connection; the failure it prevents cannot be fixed from inside the
            //     app. Drop it once x11vnc can be measured.
            dispatchKeys(keys.normalizeModifiers())
        }
    }

    /** Pulls the human-readable part out of lines like "Error: unable to connect to socket: Host is down". */
    private fun lastErrorText(state: String): String =
        state.removePrefix("Error: ").takeIf { it != "Disconnected" && it.isNotBlank() }
            ?: connectionState.let { (it as? VncConnectionState.Retrying)?.reason ?: "" }

    override fun onFatalFailure(message: String) {
        publishState(VncConnectionState.Failed(message))
        Log.e("VncSurfaceView", "Fatal: $message - not reconnecting")
        fatalFailure = true
        post { onConnectionFatal?.invoke(message) }
    }

    override fun onDesktopSizeChanged(width: Int, height: Int) {
        post { onDesktopSize?.invoke(width, height) }
        Log.d("VncSurfaceView", "Desktop size changed: $width x $height")
        vncWidth = width
        vncHeight = height
        post { pointer.setBounds(width, height) }
    }

    override fun onRequestBitmap(width: Int, height: Int): Bitmap? {
        if (vncBitmap == null || vncBitmap?.width != width || vncBitmap?.height != height) {
            vncBitmap?.recycle()
            vncBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        return vncBitmap
    }

    /**
     * 🔴 This used to be `post { renderFrame() }`, stacking a draw onto the UI thread per frame -
     *    which is exactly why touches queued up. Now it only wakes the render thread (microseconds).
     */
    override fun onFrameUpdated() {
        requestRender()
    }

    /**
     * Server clipboard to the Android clipboard.
     * 🔑 The contents are **never logged** - a clipboard may hold a password, and logcat persists.
     */
    override fun onClipboardDataReceived(data: String) {
        Log.d("VncSurfaceView", "Clipboard received: ${data.length} chars")
        if (!clipboardToAndroid) return
        post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("VNC", data))
            } catch (e: Throwable) {
                Log.e("VncSurfaceView", "could not write to the clipboard", e)
            }
        }
        onClipboardText?.invoke(data)
    }

    /** Android clipboard to the server. Called by the UI's paste button. */
    fun sendClipboardFromAndroid(): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return false
        if (text.isEmpty()) return false
        engine?.sendClientCutText(text)
        return true
    }

    /** Sends an arbitrary string to the server clipboard. */
    fun sendClipboard(text: String) {
        engine?.sendClientCutText(text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 🔴 Measured FIRST: whatever happens below is a *cause* of latency, not something to
        //    measure. eventTime is when the event was created, so the difference from now is
        //    exactly how long the UI thread was backed up.
        io.github.zirize.vncviewerforgames.perf.PerfStats.inputDispatch(
            SystemClock.uptimeMillis() - event.eventTime)
        val bmp = vncBitmap ?: return true
        val dx = (width - bmp.width) / 2f
        val dy = (height - bmp.height) / 2f

        // 🔴 Use actionMasked. Plain action has the pointer index packed into it, so the value
        //    breaks the moment a second finger lands.
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return true
        }

        val ev = TouchEvent(
            action = action,
            pointerCount = event.pointerCount,
            x = event.x - dx,
            y = event.y - dy,
            timeMs = event.eventTime,
        )

        val beforeX = pointer.cursorX
        val beforeY = pointer.cursorY
        dispatch(pointer.onEvent(ev))
        scheduleLongPress()
        if (beforeX != pointer.cursorX || beforeY != pointer.cursorY) {
            onCursorMoved?.invoke(pointer.cursorX, pointer.cursorY)
            // 🔑 No waiting for a frame - the cursor follows the finger there and then.
            if (localCursor != null) requestRender()
        }
        return true
    }

    private fun dispatch(cmds: List<PointerCommand>) {
        val e = engine ?: return
        for (c in cmds) e.sendPointerEvent(c.x, c.y, c.buttonMask)
    }

    /**
     * 🔑 event.eventTime and SystemClock.uptimeMillis() are the **same clock**, which is what lets
     * a deadline returned by the controller be converted straight into a postDelayed delay.
     */
    private fun scheduleLongPress() {
        renderHandler.removeCallbacks(longPressRunnable)
        val at = pointer.nextTimeoutAtMs() ?: return
        renderHandler.postDelayed(longPressRunnable, (at - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun dispatchPointerTimeout() {
        dispatch(pointer.onTimeout(SystemClock.uptimeMillis()))
    }

    private fun renderFrame() {
        val t0 = System.nanoTime()
        val canvas: Canvas? = holder.lockCanvas()
        if (canvas != null) {
            try {
                canvas.drawColor(Color.BLACK)

                // Draw VNC bitmap centered exactly 1:1
                vncBitmap?.let { bmp ->
                    val dx = (canvas.width - bmp.width) / 2f
                    val dy = (canvas.height - bmp.height) / 2f
                    synchronized(bmp) {
                        canvas.drawBitmap(bmp, dx, dy, paint)
                    }
                    // 🔑 The cursor is drawn outside the lock - there is no reason to wait for
                    //    commitFrame. Even over a frame-old picture, the cursor belongs where the
                    //    finger is **now**.
                    localCursor?.let { c ->
                        canvas.drawBitmap(
                            c.bmp, dx + pointer.cursorX - c.hotX, dy + pointer.cursorY - c.hotY, paint)
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        io.github.zirize.vncviewerforgames.perf.PerfStats.render((System.nanoTime() - t0) / 1_000_000)
    }

    /** Called to reconnect after a fatal failure once the settings (a password, say) have been fixed. */
    fun retryConnection() {
        fatalFailure = false
        publishState(VncConnectionState.Connecting(""))
        engine?.stop()
        engine = null
        startEngineIfNeeded()
    }

    private fun startEngineIfNeeded() {
        if (engine != null) return
        Log.d("VncSurfaceView", "Connecting to VNC server at " +
                "${connectionConfig.host}:${connectionConfig.port}")
        engine = VncEngine(connectionConfig, this)
        engine?.start()
    }

    private fun scheduleReconnect() {
        if (fatalFailure || reconnectScheduled || !holder.surface.isValid) return

        reconnectScheduled = true
        renderHandler.postDelayed({
            reconnectScheduled = false
            if (!holder.surface.isValid) return@postDelayed
            // 🔴 Checked AGAIN here: during the one-second delay, a failure that must not be
            //    retried (an auth failure) can become final. Without this check that one attempt
            //    still goes out, and on an auth failure it counts as another login attempt against
            //    the server - which can trip a lockout policy.
            //    Measured 2026-09-15: one connection went out *after* the "not reconnecting" log.
            if (fatalFailure) return@postDelayed

            engine?.stop()
            engine = null
            startEngineIfNeeded()
        }, 1000)
    }
}
