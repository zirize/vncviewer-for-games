// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import io.github.zirize.vncviewerforgames.VncSurfaceView
import io.github.zirize.vncviewerforgames.input.LatchState
import io.github.zirize.vncviewerforgames.input.VncKeySym

/**
 * The on-screen gamepad overlay, drawn down the left and right edges of the screen.
 *
 * **The rule changed on 2026-09-16.** It used to be "never draw over the VNC picture", with the
 * panels living strictly inside the black margins — which meant that when the remote resolution
 * filled the device width, **the buttons disappeared**. Now the panel width comes from the screen
 * ([panelWidthPx]) and the panels **overlap the remote picture** when they have to. The judgement
 * was that in trackpad mode a partly covered edge does not get in the way.
 *
 * 🔴 **Overlapping also breaks the old assumption that touches are separated.** Previously there
 * was no remote picture under the panel, so nothing had to be arbitrated. Now something does, and
 * the rule is one line: **a touch that misses a button is not consumed** and falls through to the
 * `VncSurfaceView` underneath.
 * 🔑 That is what keeps the overlapping band usable as trackpad (everywhere except on a button).
 * Consume it instead and 240px down each side of the screen becomes dead ground — fatal for a
 * trackpad, which is a thing you swipe anywhere.
 */
@Composable
fun GamepadPanel(
    view: VncSurfaceView,
    panel: OverlayPanel,
    widthPx: Float,
    heightPx: Float,
    minTouchPx: Float,
    profile: OverlayProfile,
    latch: Map<Int, LatchState>,
    onUi: (UiTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placed = remember(profile, panel, widthPx, heightPx) {
        layoutPanel(profile, panel, widthPx, heightPx)
    }
    // What each finger is currently holding. 🔑 Multi-touch, so it has to be per pointerId.
    val active = remember { mutableMapOf<Long, ActiveTouch>() }

    Box(
        modifier = modifier.pointerInput(placed, minTouchPx) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    for (ch in ev.changes) {
                        val id = ch.id.value
                        when (ev.type) {
                            PointerEventType.Press -> {
                                val hit = hitTest(placed, ch.position.x, ch.position.y, minTouchPx)
                                if (hit != null) {
                                    active[id] = beginTouch(view, hit, ch.position, onUi)
                                    ch.consume()
                                }
                            }
                            PointerEventType.Move -> {
                                active[id]?.let { a ->
                                    updateTouch(view, a, ch.position, minTouchPx)
                                    ch.consume()
                                }
                            }
                            PointerEventType.Release -> {
                                active.remove(id)?.let { endTouch(view, it); ch.consume() }
                            }
                            else -> {
                                // Cancel and friends - let go of whatever was held.
                                active.remove(id)?.let { endTouch(view, it) }
                            }
                        }
                    }
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            placed.forEach { drawButton(it, latchStateOf(it, latch), profile.cornerRadiusPx) }
        }
    }
}

private fun latchStateOf(p: PlacedButton, latch: Map<Int, LatchState>): LatchState {
    val a = p.button.action as? OverlayAction.Key ?: return LatchState.OFF
    return latch[a.keySym] ?: LatchState.OFF
}

/** What one finger is currently holding. */
internal class ActiveTouch(
    val placed: PlacedButton,
    /** A keysym held down by HOLD. Released when the finger lifts. */
    var heldKeySym: Int? = null,
    /** A keysym under a finger on a LATCH button. The latching layer decides what happens on lift. */
    var latchKeySym: Int? = null,
    var dpadDir: DpadDirection? = null,
)

private fun beginTouch(
    view: VncSurfaceView,
    hit: PlacedButton,
    pos: Offset,
    onUi: (UiTarget) -> Unit,
): ActiveTouch {
    val t = ActiveTouch(hit)
    // 🔴 A button the parser could not understand: still drawn, but it does nothing.
    if (!hit.button.enabled) return t
    when (val a = hit.button.action) {
        is OverlayAction.Key -> when {
            hit.button.shape == ButtonShape.DPAD -> {
                val dir = dpadDirection(hit, pos.x, pos.y)
                if (dir != null) {
                    t.dpadDir = dir
                    t.heldKeySym = dpadKeySym(dir)
                    view.sendKey(t.heldKeySym!!, true)
                }
            }
            a.behavior == KeyBehavior.HOLD -> {
                t.heldKeySym = a.keySym
                view.sendKey(a.keySym, true)
            }
            // 🔑 Both press and release are forwarded - that is what makes hold-and-use work.
            a.behavior == KeyBehavior.LATCH -> {
                t.latchKeySym = a.keySym
                view.pressModifierKey(a.keySym)
            }
            else -> view.tapKey(a.keySym)
        }
        is OverlayAction.Wheel ->
            if (a.button == io.github.zirize.vncviewerforgames.input.VncButton.WHEEL_UP)
                view.sendWheelUp(a.clicks) else view.sendWheelDown(a.clicks)
        is OverlayAction.Mouse -> view.tapMouseButton(a.button)
        is OverlayAction.Ui -> onUi(a.target)   // settings is the only one for now
    }
    return t
}

/**
 * 🔴 **The moment a finger leaves the button, let go.** Otherwise a slip becomes a stuck key, and
 * any attempt to measure D-pad misfires is hidden behind the stuck ones.
 */
private fun updateTouch(view: VncSurfaceView, t: ActiveTouch, pos: Offset, minTouchPx: Float) {
    if (!stillInside(t.placed, pos.x, pos.y, minTouchPx)) {
        releaseHeld(view, t)
        return
    }
    if (t.placed.button.shape != ButtonShape.DPAD) return
    val dir = dpadDirection(t.placed, pos.x, pos.y)
    if (dir == t.dpadDir) return
    releaseHeld(view, t)
    if (dir != null) {
        t.dpadDir = dir
        t.heldKeySym = dpadKeySym(dir)
        view.sendKey(t.heldKeySym!!, true)
    }
}

private fun endTouch(view: VncSurfaceView, t: ActiveTouch) = releaseHeld(view, t)

private fun releaseHeld(view: VncSurfaceView, t: ActiveTouch) {
    t.heldKeySym?.let { view.sendKey(it, false) }
    t.heldKeySym = null
    t.dpadDir = null
    // 🔑 For a modifier we only report the lift; whether it clears or stays armed is the latching layer's call.
    t.latchKeySym?.let { view.releaseModifierKey(it) }
    t.latchKeySym = null
}

private fun dpadKeySym(d: DpadDirection) = when (d) {
    DpadDirection.UP -> VncKeySym.Up
    DpadDirection.DOWN -> VncKeySym.Down
    DpadDirection.LEFT -> VncKeySym.Left
    DpadDirection.RIGHT -> VncKeySym.Right
}

// ── Drawing ─────────────────────────────────────────────────────────
// 🔑 The rule taken from the reference screen: dark translucent fill, white 4px outline, white text.

private val FILL = Color(0x99000000)
private val STROKE = Color(0xFFFFFFFF)
/** ONESHOT highlights the outline, LOCK inverts the fill. 🔑 Drawn from one subscription; the UI keeps no copy. */
private val LOCK_FILL = Color(0xE6FFFFFF)

private fun DrawScope.drawButton(p: PlacedButton, latch: LatchState, cornerRadiusPx: Float) {
    // 🔑 Disabled is dimmed, not hidden - hidden reads as "I deleted it by accident".
    if (!p.button.enabled) return drawDisabled(p, cornerRadiusPx)
    val locked = latch == LatchState.LOCK
    // 🔑 HELD (a finger is on it) highlights too - it should light up the instant it is touched.
    val oneshot = latch == LatchState.ONESHOT || latch == LatchState.HELD
    val fill = if (locked) LOCK_FILL else FILL
    val stroke = STROKE
    val sw = 4f * (p.width / 114f).coerceAtLeast(0.5f)
    // 🔑 Inset the outline: a button flush with the panel edge (x=0) would otherwise lose half its stroke.
    val inset = sw / 2f
    val topLeft = Offset(p.left + inset, p.top + inset)
    val size = Size(p.width - sw, p.height - sw)

    when (p.button.shape) {
        ButtonShape.CIRCLE -> {
            val r = minOf(p.width, p.height) / 2f - sw / 2f
            drawCircle(fill, r, Offset(p.centerX, p.centerY))
            drawCircle(stroke, r, Offset(p.centerX, p.centerY), style = Stroke(if (oneshot) sw * 2 else sw))
        }
        ButtonShape.DPAD -> drawDpad(p, fill, stroke, sw)
        else -> {
            // 🔴 The radius is fixed (it only follows the panel scale). Make it proportional to
            //    size and small buttons become circles, long ones become pills, and the panel
            //    stops looking like one family.
            val corner = cornerRadiusPx * (p.width / p.button.w)
            drawRoundRect(fill, topLeft, size,
                androidx.compose.ui.geometry.CornerRadius(corner, corner))
            drawRoundRect(stroke, topLeft, size,
                androidx.compose.ui.geometry.CornerRadius(corner, corner),
                style = Stroke(if (oneshot) sw * 2 else sw))
            // 🔑 A mouse seen from above: body plus left/right buttons. Matches the MWUP/MWDN naming.
            if (p.button.shape == ButtonShape.MOUSE) drawMouseButtons(p, fill, stroke, sw)
            // 🔑 An icon button has no text, so the drawing is the only explanation. Settings = three dots.
            if (p.button.shape == ButtonShape.ICON) drawDots(p, if (locked) Color.Black else STROKE)
        }
    }

    p.button.label?.let { label ->
        drawIntoCanvasText(label, p, if (locked) Color.Black else STROKE)
    }
}

/** The two small shapes above the body - the mouse's left and right buttons. Measured: 12x12, 19px above the body, at 38/78px across. */
private fun DrawScope.drawMouseButtons(p: PlacedButton, fill: Color, stroke: Color, sw: Float) {
    val k = p.width / p.button.w          // authored -> actual scale
    val s = 12f * k
    val top = p.top - 19f * k
    listOf(38f, 78f).forEach { dx ->
        val o = Offset(p.left + dx * k, top)
        val sz = Size(s, s)
        drawRoundRect(fill, o, sz, androidx.compose.ui.geometry.CornerRadius(3f * k, 3f * k))
        drawRoundRect(stroke, o, sz, androidx.compose.ui.geometry.CornerRadius(3f * k, 3f * k),
            style = Stroke(sw * 0.6f))
    }
}

/** Three dots - the conventional "more / settings" mark. The reference screen had one here too. */
private fun DrawScope.drawDots(p: PlacedButton, color: Color) {
    val r = minOf(p.width, p.height) * 0.07f
    val gap = r * 3.2f
    for (i in -1..1) drawCircle(color, r, Offset(p.centerX + i * gap, p.centerY))
}

private fun DrawScope.drawDpad(p: PlacedButton, fill: Color, stroke: Color, sw: Float) {
    // A cross of four arms. The middle is left empty (the no-direction zone).
    val cx = p.centerX
    val cy = p.centerY
    val armW = p.width * 0.30f
    val armH = p.height * 0.30f
    listOf(
        Offset(cx - armW / 2, p.top) to Size(armW, armH),                    // up
        Offset(cx - armW / 2, p.bottom - armH) to Size(armW, armH),          // down
        Offset(p.left, cy - armH / 2) to Size(armW, armH),                   // left
        Offset(p.right - armW, cy - armH / 2) to Size(armW, armH),           // right
    ).forEach { (o, s) ->
        drawRoundRect(fill, o, s, androidx.compose.ui.geometry.CornerRadius(12f, 12f))
        drawRoundRect(stroke, o, s, androidx.compose.ui.geometry.CornerRadius(12f, 12f),
            style = Stroke(sw))
    }
}

private fun DrawScope.drawIntoCanvasText(text: String, p: PlacedButton, color: Color) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.value.toLong().let { android.graphics.Color.argb(
            (color.alpha * 255).toInt(), (color.red * 255).toInt(),
            (color.green * 255).toInt(), (color.blue * 255).toInt()) }
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = (minOf(p.width, p.height) * if (text.length > 2) 0.26f else 0.45f)
        isFakeBoldText = true
    }
    val baseline = p.centerY - (paint.descent() + paint.ascent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, p.centerX, baseline, paint)
}

/** 🔑 A disabled button: outline only, dimmed, so it reads as "present but wrong" rather than gone. */
private fun DrawScope.drawDisabled(p: PlacedButton, cornerRadiusPx: Float) {
    val sw = 4f * (p.width / 114f).coerceAtLeast(0.5f)
    val inset = sw / 2f
    val corner = cornerRadiusPx * (p.width / p.button.w)
    drawRoundRect(
        Color(0x33FFFFFF),
        Offset(p.left + inset, p.top + inset),
        Size(p.width - sw, p.height - sw),
        androidx.compose.ui.geometry.CornerRadius(corner, corner),
        style = Stroke(width = sw),
    )
}
