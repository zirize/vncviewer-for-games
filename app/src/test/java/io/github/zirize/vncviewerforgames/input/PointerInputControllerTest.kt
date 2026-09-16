// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerInputControllerTest {

    private fun absolute() = PointerInputController(PointerConfig(mode = PointerMode.ABSOLUTE))
        .apply { setBounds(1920, 1080) }

    @Test
    fun absoluteMode_downMapsToExactPointAndPressesLeft() {
        val c = absolute()
        val out = c.onEvent(TouchEvent(TouchAction.DOWN, 1, 300f, 400f, 0))
        assertEquals(listOf(PointerCommand(300, 400, VncButton.LEFT)), out)
    }

    @Test
    fun absoluteMode_upReleasesAtSamePoint() {
        val c = absolute()
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 300f, 400f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.UP, 1, 300f, 400f, 50))
        assertEquals(listOf(PointerCommand(300, 400, VncButton.NONE)), out)
    }

    @Test
    fun cursorIsClampedToBounds() {
        val c = absolute()
        val out = c.onEvent(TouchEvent(TouchAction.DOWN, 1, -50f, 5000f, 0))
        assertEquals(listOf(PointerCommand(0, 1079, VncButton.LEFT)), out)
    }

    @Test
    fun eventsBeforeBoundsAreIgnored() {
        val c = PointerInputController(PointerConfig(mode = PointerMode.ABSOLUTE))
        assertEquals(emptyList<PointerCommand>(), c.onEvent(TouchEvent(TouchAction.DOWN, 1, 10f, 10f, 0)))
    }

    private fun trackpad(cfg: PointerConfig = PointerConfig()) =
        PointerInputController(cfg).apply { setBounds(1920, 1080) }

    @Test
    fun trackpad_movesCursorByDeltaTimesSensitivity() {
        val c = trackpad(PointerConfig(sensitivity = 2f, moveSlopPx = 5f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.MOVE, 1, 150f, 100f, 20))
        assertEquals(listOf(PointerCommand(1060, 540, VncButton.NONE)), out)
    }

    @Test
    fun trackpad_downAloneSendsNothing() {
        val c = trackpad()
        assertEquals(emptyList<PointerCommand>(), c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0)))
    }

    @Test
    fun trackpad_movementUnderSlopDoesNotMoveCursor() {
        val c = trackpad(PointerConfig(moveSlopPx = 20f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.MOVE, 1, 105f, 100f, 10))
        assertEquals(emptyList<PointerCommand>(), out)
        assertEquals(960, c.cursorX)
    }

    @Test
    fun trackpad_cursorClampsAtEdge() {
        val c = trackpad(PointerConfig(sensitivity = 1f, moveSlopPx = 1f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 0f, 0f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.MOVE, 1, -5000f, 0f, 20))
        assertEquals(listOf(PointerCommand(0, 540, VncButton.NONE)), out)
    }

    @Test
    fun trackpad_tapSendsLeftClick() {
        val c = trackpad()
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 80))
        assertEquals(
            listOf(PointerCommand(960, 540, VncButton.LEFT), PointerCommand(960, 540, VncButton.NONE)),
            out
        )
    }

    @Test
    fun trackpad_slowReleaseIsNotATap() {
        val c = trackpad(PointerConfig(tapMaxMs = 250, longPressRightClick = false))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 400))
        assertEquals(emptyList<PointerCommand>(), out)
    }

    @Test
    fun trackpad_moveThenReleaseIsNotATap() {
        val c = trackpad(PointerConfig(moveSlopPx = 5f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.MOVE, 1, 200f, 100f, 20))
        val out = c.onEvent(TouchEvent(TouchAction.UP, 1, 200f, 100f, 40))
        assertEquals(emptyList<PointerCommand>(), out)
    }

    @Test
    fun trackpad_tapToClickDisabledSendsNothing() {
        val c = trackpad(PointerConfig(tapToClick = false))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 80))
        assertEquals(emptyList<PointerCommand>(), out)
    }

    @Test
    fun longPress_sendsRightClickAndNoLeftClick() {
        val c = trackpad(PointerConfig(longPressMs = 500))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        assertEquals(500L, c.nextTimeoutAtMs())
        val out = c.onTimeout(500)
        assertEquals(
            listOf(PointerCommand(960, 540, VncButton.RIGHT), PointerCommand(960, 540, VncButton.NONE)),
            out
        )
        assertEquals(emptyList<PointerCommand>(), c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 600)))
    }

    @Test
    fun longPress_notFiredBeforeDeadline() {
        val c = trackpad(PointerConfig(longPressMs = 500))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        assertEquals(emptyList<PointerCommand>(), c.onTimeout(499))
    }

    @Test
    fun longPress_cancelledByMovement() {
        val c = trackpad(PointerConfig(longPressMs = 500, moveSlopPx = 5f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.MOVE, 1, 200f, 100f, 20))
        assertEquals(null, c.nextTimeoutAtMs())
        assertEquals(emptyList<PointerCommand>(), c.onTimeout(500))
    }

    @Test
    fun longPress_disabledSendsNothing() {
        val c = trackpad(PointerConfig(longPressRightClick = false))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        assertEquals(null, c.nextTimeoutAtMs())
        assertEquals(emptyList<PointerCommand>(), c.onTimeout(500))
    }

    @Test
    fun longPress_worksInAbsoluteModeAtTouchPoint() {
        val c = absolute()
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 300f, 400f, 0))
        val out = c.onTimeout(500)
        assertEquals(
            listOf(
                PointerCommand(300, 400, VncButton.LEFT or VncButton.RIGHT),
                PointerCommand(300, 400, VncButton.LEFT),
            ),
            out
        )
    }

    /** 🔴 A long-press timer surviving into a drag fires a right click at the wrong moment. */
    @Test
    fun longPress_cancelledByDragInAbsoluteMode() {
        val c = PointerInputController(PointerConfig(mode = PointerMode.ABSOLUTE, moveSlopPx = 5f))
            .apply { setBounds(1920, 1080) }
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 300f, 400f, 0))
        c.onEvent(TouchEvent(TouchAction.MOVE, 1, 500f, 400f, 30))
        assertEquals(null, c.nextTimeoutAtMs())
        assertEquals(emptyList<PointerCommand>(), c.onTimeout(500))
    }

    @Test
    fun tapAndAHalf_holdsLeftButtonWhileMoving() {
        val c = trackpad(PointerConfig(sensitivity = 1f, moveSlopPx = 5f, dragChainMs = 300))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 50))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 100))
        val moved = c.onEvent(TouchEvent(TouchAction.MOVE, 1, 130f, 100f, 120))
        assertEquals(listOf(PointerCommand(990, 540, VncButton.LEFT)), moved)
        val up = c.onEvent(TouchEvent(TouchAction.UP, 1, 130f, 100f, 200))
        assertEquals(listOf(PointerCommand(990, 540, VncButton.NONE)), up)
    }

    @Test
    fun tapAndAHalf_expiresAfterDragChainWindow() {
        val c = trackpad(PointerConfig(dragChainMs = 300, moveSlopPx = 5f, sensitivity = 1f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 50))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 500))
        val moved = c.onEvent(TouchEvent(TouchAction.MOVE, 1, 130f, 100f, 520))
        assertEquals(listOf(PointerCommand(990, 540, VncButton.NONE)), moved)
    }

    @Test
    fun tapAndAHalf_disabledDoesNotDrag() {
        val c = trackpad(PointerConfig(tapAndAHalfDrag = false, moveSlopPx = 5f, sensitivity = 1f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 50))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 100))
        val moved = c.onEvent(TouchEvent(TouchAction.MOVE, 1, 130f, 100f, 120))
        assertEquals(listOf(PointerCommand(990, 540, VncButton.NONE)), moved)
    }

    @Test
    fun twoFinger_verticalScrollEmitsWheelPerStep() {
        val c = trackpad(PointerConfig(scrollStepPx = 40f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        val out = c.onEvent(TouchEvent(TouchAction.MOVE, 2, 100f, 200f, 30))
        assertEquals(
            listOf(
                PointerCommand(960, 540, VncButton.WHEEL_DOWN), PointerCommand(960, 540, VncButton.NONE),
                PointerCommand(960, 540, VncButton.WHEEL_DOWN), PointerCommand(960, 540, VncButton.NONE),
            ),
            out
        )
    }

    @Test
    fun twoFinger_naturalScrollInvertsDirection() {
        val c = trackpad(PointerConfig(scrollStepPx = 40f, naturalScroll = true))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        val out = c.onEvent(TouchEvent(TouchAction.MOVE, 2, 100f, 150f, 30))
        assertEquals(
            listOf(PointerCommand(960, 540, VncButton.WHEEL_UP), PointerCommand(960, 540, VncButton.NONE)),
            out
        )
    }

    @Test
    fun twoFinger_scrollDisabledSendsNothing() {
        val c = trackpad(PointerConfig(twoFingerScroll = false, scrollStepPx = 40f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        assertEquals(emptyList<PointerCommand>(), c.onEvent(TouchEvent(TouchAction.MOVE, 2, 100f, 300f, 30)))
    }

    @Test
    fun twoFinger_tapSendsRightClick() {
        val c = trackpad()
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        c.onEvent(TouchEvent(TouchAction.POINTER_UP, 1, 100f, 100f, 80))
        val out = c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 90))
        assertEquals(
            listOf(PointerCommand(960, 540, VncButton.RIGHT), PointerCommand(960, 540, VncButton.NONE)),
            out
        )
    }

    @Test
    fun twoFinger_tapDisabledSendsNothing() {
        val c = trackpad(PointerConfig(twoFingerTapRightClick = false))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        c.onEvent(TouchEvent(TouchAction.POINTER_UP, 1, 100f, 100f, 80))
        assertEquals(emptyList<PointerCommand>(), c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 100f, 90)))
    }

    @Test
    fun twoFinger_scrollThenReleaseIsNotARightClick() {
        val c = trackpad(PointerConfig(scrollStepPx = 40f))
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        c.onEvent(TouchEvent(TouchAction.MOVE, 2, 100f, 200f, 30))
        c.onEvent(TouchEvent(TouchAction.POINTER_UP, 1, 100f, 200f, 40))
        assertEquals(emptyList<PointerCommand>(), c.onEvent(TouchEvent(TouchAction.UP, 1, 100f, 200f, 50)))
    }

    @Test
    fun twoFinger_downCancelsPendingLongPress() {
        val c = trackpad()
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        assertEquals(null, c.nextTimeoutAtMs())
    }

    @Test
    fun cancelReleasesHeldButtons() {
        val c = absolute()
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 300f, 400f, 0))
        val out = c.onEvent(TouchEvent(TouchAction.CANCEL, 1, 300f, 400f, 50))
        assertEquals(listOf(PointerCommand(300, 400, VncButton.NONE)), out)
    }

    @Test
    fun releaseAllClearsHeldButtons() {
        val c = absolute()
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 300f, 400f, 0))
        assertEquals(listOf(PointerCommand(300, 400, VncButton.NONE)), c.releaseAll())
        assertEquals(emptyList<PointerCommand>(), c.releaseAll())
    }

    // ── Scroll sensitivity and direction ────────────────────────────

    /** How many wheel clicks come out of moving two fingers down by dy. */
    private fun wheelClicks(cfg: PointerConfig, dy: Float): Int {
        val c = trackpad(cfg)
        c.onEvent(TouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0))
        c.onEvent(TouchEvent(TouchAction.POINTER_DOWN, 2, 100f, 100f, 10))
        val out = c.onEvent(TouchEvent(TouchAction.MOVE, 2, 100f, 100f + dy, 30))
        return out.count { it.buttonMask != VncButton.NONE }
    }

    @Test
    fun scrollSensitivity_defaultIsOneClickPerStep() {
        assertEquals(2, wheelClicks(PointerConfig(scrollStepPx = 40f), 80f))
    }

    @Test
    fun scrollSensitivity_higherMeansMoreClicksForSameMovement() {
        assertEquals(4, wheelClicks(PointerConfig(scrollStepPx = 40f, scrollSensitivity = 2f), 80f))
    }

    @Test
    fun scrollSensitivity_lowerMeansFewerClicks() {
        assertEquals(1, wheelClicks(PointerConfig(scrollStepPx = 40f, scrollSensitivity = 0.5f), 80f))
    }

    @Test
    fun scrollSensitivity_zeroOrNegativeFallsBackToOne() {
        assertEquals(2, wheelClicks(PointerConfig(scrollStepPx = 40f, scrollSensitivity = 0f), 80f))
        assertEquals(2, wheelClicks(PointerConfig(scrollStepPx = 40f, scrollSensitivity = -3f), 80f))
    }

    @Test
    fun scrollSensitivity_veryHighDoesNotDivideByZeroStep() {
        // The effective step must not drop below 1px, or the loop never terminates
        assertEquals(80, wheelClicks(PointerConfig(scrollStepPx = 40f, scrollSensitivity = 10000f), 80f))
    }
}
