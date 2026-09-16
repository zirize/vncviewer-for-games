// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

/**
 * Translates touch gestures into RFB PointerEvents.
 * 🔴 Main thread only — no synchronisation.
 */
class PointerInputController(var config: PointerConfig = PointerConfig()) {

    private enum class State { IDLE, PENDING, MOVING, CONSUMED, TAP_WAIT, DRAGGING, TWO_FINGER }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTimeMs = 0L
    private var accX = 0f
    private var accY = 0f
    private var longPressAtMs: Long? = null
    private var tapWaitUntilMs = 0L
    private var twoFingerStartMs = 0L
    private var twoFingerScrolled = false
    private var scrollAccX = 0f
    private var scrollAccY = 0f

    private var boundsW = 0
    private var boundsH = 0
    private var hasBounds = false

    var cursorX = 0; private set
    var cursorY = 0; private set

    /** Buttons currently held (they must survive a drag). */
    private var heldMask = VncButton.NONE

    fun setBounds(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        boundsW = width
        boundsH = height
        if (!hasBounds) {
            hasBounds = true
            cursorX = width / 2
            cursorY = height / 2
        } else {
            moveCursorTo(cursorX.toFloat(), cursorY.toFloat())
        }
    }

    fun onEvent(ev: TouchEvent): List<PointerCommand> {
        if (!hasBounds) return emptyList()
        // 🔑 These three are mode-independent: two-finger gestures and cancel behave the same in both.
        if (ev.action == TouchAction.CANCEL) return onCancel()
        if (ev.action == TouchAction.POINTER_DOWN) return onTwoFingerDown(ev)
        if (state == State.TWO_FINGER) return onTwoFinger(ev)
        return when (config.mode) {
            PointerMode.ABSOLUTE -> onAbsolute(ev)
            PointerMode.TRACKPAD -> onTrackpad(ev)
        }
    }

    private fun onAbsolute(ev: TouchEvent): List<PointerCommand> = when (ev.action) {
        TouchAction.DOWN -> {
            moveCursorTo(ev.x, ev.y)
            downX = ev.x; downY = ev.y
            armLongPress(ev.timeMs)
            press(VncButton.LEFT)
        }
        TouchAction.MOVE -> {
            // 🔴 Starting a drag MUST cancel long-press, or a right click fires mid-drag.
            if (exceededSlop(ev)) longPressAtMs = null
            moveCursorTo(ev.x, ev.y)
            listOf(cursorCmd())
        }
        TouchAction.UP -> { moveCursorTo(ev.x, ev.y); longPressAtMs = null; release(VncButton.LEFT) }
        else -> emptyList()
    }

    private fun onTrackpad(ev: TouchEvent): List<PointerCommand> = when (ev.action) {
        TouchAction.DOWN -> {
            val chaining = state == State.TAP_WAIT && ev.timeMs <= tapWaitUntilMs
            state = if (chaining) State.DRAGGING else State.PENDING
            downX = ev.x; downY = ev.y
            lastX = ev.x; lastY = ev.y
            downTimeMs = ev.timeMs
            accX = 0f; accY = 0f
            if (chaining) {
                longPressAtMs = null
                press(VncButton.LEFT)
            } else {
                armLongPress(ev.timeMs)
                emptyList()
            }
        }
        TouchAction.MOVE -> onTrackpadMove(ev)
        TouchAction.UP -> onTrackpadUp(ev)
        else -> emptyList()
    }

    private fun onTrackpadUp(ev: TouchEvent): List<PointerCommand> {
        longPressAtMs = null
        if (state == State.DRAGGING) {
            state = State.IDLE
            return release(VncButton.LEFT)
        }
        val wasPending = state == State.PENDING
        state = State.IDLE
        if (!wasPending) return emptyList()
        if (!config.tapToClick) return emptyList()
        if (ev.timeMs - downTimeMs > config.tapMaxMs) return emptyList()
        if (config.tapAndAHalfDrag) {
            state = State.TAP_WAIT
            tapWaitUntilMs = ev.timeMs + config.dragChainMs
        }
        return click(VncButton.LEFT)
    }

    private fun onTwoFingerDown(ev: TouchEvent): List<PointerCommand> {
        longPressAtMs = null
        val releases = releaseAll()
        state = State.TWO_FINGER
        twoFingerStartMs = ev.timeMs
        twoFingerScrolled = false
        scrollAccX = 0f; scrollAccY = 0f
        lastX = ev.x; lastY = ev.y
        return releases
    }

    private fun onTwoFinger(ev: TouchEvent): List<PointerCommand> = when (ev.action) {
        TouchAction.MOVE -> onTwoFingerMove(ev)
        TouchAction.POINTER_UP -> emptyList()   // decided on the final UP
        TouchAction.UP -> onTwoFingerUp(ev)
        else -> emptyList()
    }

    private fun onTwoFingerMove(ev: TouchEvent): List<PointerCommand> {
        if (!config.twoFingerScroll) { lastX = ev.x; lastY = ev.y; return emptyList() }
        val sign = if (config.naturalScroll) -1f else 1f
        scrollAccX += (ev.x - lastX) * sign
        scrollAccY += (ev.y - lastY) * sign
        lastX = ev.x; lastY = ev.y

        val step = effectiveScrollStep()
        if (step <= 0f) return emptyList()
        val out = ArrayList<PointerCommand>()
        while (scrollAccY >= step) { scrollAccY -= step; out += click(VncButton.WHEEL_DOWN) }
        while (scrollAccY <= -step) { scrollAccY += step; out += click(VncButton.WHEEL_UP) }
        while (scrollAccX >= step) { scrollAccX -= step; out += click(VncButton.WHEEL_RIGHT) }
        while (scrollAccX <= -step) { scrollAccX += step; out += click(VncButton.WHEEL_LEFT) }
        if (out.isNotEmpty()) twoFingerScrolled = true
        return out
    }

    /**
     * Pixels per wheel click.
     * 🔑 Floored at 1px — at 0 the while loop below never terminates.
     */
    private fun effectiveScrollStep(): Float {
        val sens = if (config.scrollSensitivity > 0f) config.scrollSensitivity else 1f
        return (config.scrollStepPx / sens).coerceAtLeast(1f)
    }

    private fun onTwoFingerUp(ev: TouchEvent): List<PointerCommand> {
        state = State.IDLE
        if (!config.twoFingerTapRightClick) return emptyList()
        if (twoFingerScrolled) return emptyList()
        if (ev.timeMs - twoFingerStartMs > config.tapMaxMs) return emptyList()
        return click(VncButton.RIGHT)
    }

    private fun onCancel(): List<PointerCommand> {
        state = State.IDLE
        longPressAtMs = null
        return releaseAll()
    }

    /**
     * Releases every held button.
     * 🔴 **Must** be called when the connection drops, or a button stays down on the server.
     */
    fun releaseAll(): List<PointerCommand> {
        if (heldMask == VncButton.NONE) return emptyList()
        heldMask = VncButton.NONE
        return listOf(cursorCmd())
    }

    // ── How the UI (on-screen buttons) drives the mouse ─────────────────
    // 🔴 It has to go through here. If the UI called the engine directly there would be two
    //    versions of the truth for cursorX/cursorY and heldMask, and the very next touch would
    //    snap the cursor back.

    /**
     * Press and release one button, preserving anything already held ([heldMask]).
     * 🚫 There is deliberately no "hold this mouse button" API — no reason to recreate on the
     *    mouse the stuck-key hazard the key side exists to prevent, and nothing asks for it.
     */
    fun tapButton(button: Int): List<PointerCommand> {
        if (!hasBounds) return emptyList()
        return click(button)
    }

    /**
     * Turns the wheel [clicks] times.
     * 🔑 **The wheel is a button.** RFB has no wheel event: one click is a down/up pair on the
     * corresponding bit. [button] is [VncButton.WHEEL_UP], [VncButton.WHEEL_DOWN], and so on.
     */
    fun wheel(button: Int, clicks: Int): List<PointerCommand> {
        if (!hasBounds || clicks <= 0) return emptyList()
        val n = clicks.coerceAtMost(MAX_WHEEL_CLICKS)
        val out = ArrayList<PointerCommand>(n * 2)
        repeat(n) { out.addAll(click(button)) }
        return out
    }

    private fun armLongPress(nowMs: Long) {
        longPressAtMs = if (config.longPressRightClick) nowMs + config.longPressMs else null
    }

    /** When the adapter should set a timer. null means there is nothing to set. */
    fun nextTimeoutAtMs(): Long? = longPressAtMs

    /** Decides whether the long press has expired. The caller supplies the time, so tests stay deterministic. */
    fun onTimeout(nowMs: Long): List<PointerCommand> {
        val deadline = longPressAtMs ?: return emptyList()
        if (nowMs < deadline) return emptyList()
        longPressAtMs = null
        if (state == State.PENDING) state = State.CONSUMED
        return click(VncButton.RIGHT)
    }

    /** A press/release pair, preserving anything already held. */
    private fun click(button: Int): List<PointerCommand> {
        val down = PointerCommand(cursorX, cursorY, heldMask or button)
        val up = PointerCommand(cursorX, cursorY, heldMask)
        return listOf(down, up)
    }

    private fun onTrackpadMove(ev: TouchEvent): List<PointerCommand> {
        if (state == State.PENDING) {
            if (!exceededSlop(ev)) return emptyList()
            state = State.MOVING
            longPressAtMs = null
        }
        if (state != State.MOVING && state != State.CONSUMED && state != State.DRAGGING) {
            return emptyList()
        }
        val cmds = moveCursorBy(ev.x - lastX, ev.y - lastY)
        lastX = ev.x; lastY = ev.y
        return cmds
    }

    private fun exceededSlop(ev: TouchEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        return dx * dx + dy * dy >= config.moveSlopPx * config.moveSlopPx
    }

    /** Accumulates fractional deltas so they are not thrown away; only whole pixels reach the cursor. */
    private fun moveCursorBy(dx: Float, dy: Float): List<PointerCommand> {
        accX += dx * config.sensitivity
        accY += dy * config.sensitivity
        val stepX = accX.toInt()
        val stepY = accY.toInt()
        accX -= stepX
        accY -= stepY
        if (stepX == 0 && stepY == 0) return emptyList()
        cursorX = (cursorX + stepX).coerceIn(0, boundsW - 1)
        cursorY = (cursorY + stepY).coerceIn(0, boundsH - 1)
        return listOf(cursorCmd())
    }

    private fun moveCursorTo(x: Float, y: Float) {
        cursorX = x.toInt().coerceIn(0, boundsW - 1)
        cursorY = y.toInt().coerceIn(0, boundsH - 1)
    }

    private fun cursorCmd() = PointerCommand(cursorX, cursorY, heldMask)

    private fun press(button: Int): List<PointerCommand> {
        heldMask = heldMask or button
        return listOf(cursorCmd())
    }

    private fun release(button: Int): List<PointerCommand> {
        heldMask = heldMask and button.inv()
        return listOf(cursorCmd())
    }

    private companion object {
        /** 🔑 A cap so that rapid repeats cannot balloon the send queue. */
        const val MAX_WHEEL_CLICKS = 20
    }
}
