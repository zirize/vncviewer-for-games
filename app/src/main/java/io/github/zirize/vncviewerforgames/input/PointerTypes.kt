// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

/** How a touch is read. TRACKPAD = relative movement, ABSOLUTE = jump to where you touched. */
enum class PointerMode { TRACKPAD, ABSOLUTE }

enum class TouchAction { DOWN, MOVE, UP, POINTER_DOWN, POINTER_UP, CANCEL }

/**
 * A value type standing in for Android's MotionEvent.
 * 🔑 It exists so the controllers never touch `android.*` — that is what makes them testable
 * without a device. x and y are in **framebuffer coordinates**; timeMs is supplied by the caller.
 */
data class TouchEvent(
    val action: TouchAction,
    val pointerCount: Int,
    val x: Float,
    val y: Float,
    val timeMs: Long,
)

/** One RFB PointerEvent. */
data class PointerCommand(val x: Int, val y: Int, val buttonMask: Int)

/** RFB button mask bits. */
object VncButton {
    const val NONE = 0
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 4
    const val WHEEL_UP = 8
    const val WHEEL_DOWN = 16
    const val WHEEL_LEFT = 32
    const val WHEEL_RIGHT = 64
}

/**
 * Settings the UI reads and writes. Each Boolean is one toggle.
 * 🔑 A gesture whose flag is off produces **no commands at all** and takes no part in deciding
 * what other gestures are.
 */
data class PointerConfig(
    var mode: PointerMode = PointerMode.TRACKPAD,
    // features on/off
    var tapToClick: Boolean = true,
    var longPressRightClick: Boolean = true,
    var twoFingerTapRightClick: Boolean = true,
    var twoFingerScroll: Boolean = true,
    var tapAndAHalfDrag: Boolean = true,
    // feel
    var sensitivity: Float = 1.2f,
    var longPressMs: Long = 500,
    var tapMaxMs: Long = 250,
    var dragChainMs: Long = 300,
    var moveSlopPx: Float = 12f,
    /** Baseline pixels per wheel click. scrollSensitivity divides into this. */
    var scrollStepPx: Float = 40f,
    /**
     * Scroll sensitivity multiplier. Higher means a small movement scrolls further.
     * Effective step = scrollStepPx / scrollSensitivity, floored at 1px.
     * Anything at or below 0 is read as 1.
     */
    var scrollSensitivity: Float = 1f,
    /** Scroll direction. true = content moves with the finger (the macOS default). */
    var naturalScroll: Boolean = false,
)
