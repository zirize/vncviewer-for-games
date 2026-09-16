// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import kotlin.math.max

/** One button as actually placed on screen. Coordinates are pixels **within the panel**. */
data class PlacedButton(
    val button: OverlayButton,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

/** Which way the finger went inside the D-pad. */
enum class DpadDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Panel width — 🔴 derived from the **device screen**, not from the remote resolution.
 *
 * It used to be `margin = (screen width − remote width) / 2`, used directly as the panel width.
 * So when the remote resolution filled the device width the margin became 0 and **every button
 * disappeared**. That is what the "there are no on-screen buttons" report on 2026-09-16 was: the
 * bench server was 2340×1080, leaving a 30px margin.
 *
 * 🔑 **Why the screen *height*** — the layout was authored as "240 margin on a 1080-high screen",
 * so keeping that ratio (2/9) keeps the buttons the same fraction of the screen on any device.
 * Derive it from the width instead and buttons get thin or fat as the aspect ratio changes.
 * On this device (2400×1080), 1080 × 2/9 = **exactly 240**, i.e. identical to the old behaviour
 * back when the remote was 1920 wide.
 *
 * 🔴 The cost: when the remote screen is wide, **the buttons sit on top of it.** That was judged
 * acceptable — in trackpad mode a partially covered edge does not get in the way — and the buttons
 * are 60% black with a white outline so they stay readable over a bright picture.
 * 🔑 Covered or not, **a press that misses a button passes straight through**: when [hitTest]
 * returns null, [GamepadPanel] does not consume the event, so the trackpad keeps working.
 */
fun panelWidthPx(screenHeightPx: Float, authoredMarginPx: Int, authoredHeightPx: Int): Float =
    screenHeightPx * (authoredMarginPx.toFloat() / authoredHeightPx.toFloat())

/** Convenience form using whatever authoring basis the profile declared. */
fun panelWidthPx(screenHeightPx: Float, profile: OverlayProfile): Float =
    panelWidthPx(screenHeightPx, profile.authoredMarginPx, profile.authoredHeightPx)

/**
 * How much of the remote screen one panel covers. **0 means it covers nothing**, the old behaviour.
 *
 * 🔑 Split out so the reason can be logged: by eye you cannot tell *why* a button is sitting on
 * top of the picture.
 *
 * 🔴 **It has to be clamped at the top as well** — if the remote is *wider* than the screen the
 * margin goes negative and the covered width exceeds the panel width (a 3840-wide remote produced
 * 960px). A panel cannot cover more than it is wide.
 * Caught by a unit test on 2026-09-16: the value only feeds a log line, so on the device it would
 * have been invisible.
 */
fun panelOverlapPx(screenWidthPx: Float, remoteWidthPx: Float, panelWidthPx: Float): Float =
    (panelWidthPx - (screenWidthPx - remoteWidthPx) / 2f).coerceIn(0f, panelWidthPx)

/**
 * Maps authored coordinates onto the real panel size.
 *
 * 🔑 **The whole panel is scaled by one factor.** Scale per button and the spacing collapses —
 * buttons end up touching or overlapping. Vertically there is no scaling at all; [OverlayAnchor]
 * absorbs the difference by pinning each button to the top or the bottom.
 *
 * On this device (2400×1080) [panelWidthPx] comes out at 240, so the factor is exactly 1 —
 * confirmed by photographing the device on 2026-09-16. Other aspect ratios need that device to say.
 */
fun layoutPanel(
    buttons: List<OverlayButton>,
    panel: OverlayPanel,
    panelWidthPx: Float,
    panelHeightPx: Float,
    authoredMarginPx: Int,
): List<PlacedButton> {
    val scale = panelWidthPx / authoredMarginPx
    return buttons.filter { it.panel == panel }.map { b ->
        val w = b.w * scale
        val h = b.h * scale
        // 🔑 x is measured from the OUTER edge: the left edge for the left panel, the right edge
        //    for the right one.
        val left = when (panel) {
            OverlayPanel.LEFT -> b.x * scale
            OverlayPanel.RIGHT -> panelWidthPx - (b.x * scale) - w
        }
        val top = when (b.anchor) {
            OverlayAnchor.TOP -> b.y * scale
            OverlayAnchor.BOTTOM -> panelHeightPx - (b.y * scale) - h
        }
        PlacedButton(b, left, top, w, h)
    }
}

/**
 * Which button was pressed, or null.
 *
 * 🔴 **Touch targets are expanded to [minTouchPx].** The buttons are 43.4dp, short of the 48dp
 * Material minimum; this grows what you can *touch* without changing what is *drawn*.
 * 🔴 **Expanding them necessarily makes them overlap** — the gaps are only 4.6dp. Where they do,
 * the button whose **centre** is nearer wins, splitting the overlapping band down the middle.
 */
fun hitTest(placed: List<PlacedButton>, x: Float, y: Float, minTouchPx: Float): PlacedButton? {
    var best: PlacedButton? = null
    var bestDist = Float.MAX_VALUE
    for (p in placed) {
        // Grow only by what is missing — a button that is already big enough (the D-pad) is left alone.
        val padX = max(0f, (minTouchPx - p.width) / 2f)
        val padY = max(0f, (minTouchPx - p.height) / 2f)
        if (x < p.left - padX || x > p.right + padX) continue
        if (y < p.top - padY || y > p.bottom + padY) continue
        val dx = x - p.centerX
        val dy = y - p.centerY
        val d = dx * dx + dy * dy
        if (d < bestDist) { bestDist = d; best = p }
    }
    return best
}

/**
 * Resolves a direction inside the D-pad. The middle [deadZone] fraction is no direction (null).
 *
 * 🔑 **The D-pad is one button with directions resolved inside it.** Four separate buttons would
 * each get expanded to 48dp, the targets would overlap, and up and left would fire together.
 * 🔑 Whichever axis is further from centre wins, so a diagonal produces exactly one direction.
 */
fun dpadDirection(p: PlacedButton, x: Float, y: Float, deadZone: Float = 0.25f): DpadDirection? {
    val nx = (x - p.centerX) / (p.width / 2f)
    val ny = (y - p.centerY) / (p.height / 2f)
    val ax = kotlin.math.abs(nx)
    val ay = kotlin.math.abs(ny)
    if (max(ax, ay) < deadZone) return null
    return if (ax >= ay) {
        if (nx < 0) DpadDirection.LEFT else DpadDirection.RIGHT
    } else {
        if (ny < 0) DpadDirection.UP else DpadDirection.DOWN
    }
}

/**
 * Is the finger still **on** this button? If it has left, the key must be released at once.
 *
 * 🔴 Without this, a finger sliding off shows up as a stuck key — and then you cannot measure
 * D-pad misfires, because the misfires are hidden behind the stuck ones.
 */
fun stillInside(p: PlacedButton, x: Float, y: Float, minTouchPx: Float): Boolean {
    val padX = max(0f, (minTouchPx - p.width) / 2f)
    val padY = max(0f, (minTouchPx - p.height) / 2f)
    return x >= p.left - padX && x <= p.right + padX &&
        y >= p.top - padY && y <= p.bottom + padY
}

/** 🔑 So the overlay cannot lock itself: there must always be at least one way into settings. */
fun hasSettingsEntry(buttons: List<OverlayButton>): Boolean =
    buttons.any { (it.action as? OverlayAction.Ui)?.target == UiTarget.SETTINGS }

/** Lays out a whole profile, so callers do not have to carry the authoring basis separately. */
fun layoutPanel(profile: OverlayProfile, panel: OverlayPanel, panelWidthPx: Float, panelHeightPx: Float)
    : List<PlacedButton> =
    layoutPanel(profile.buttons, panel, panelWidthPx, panelHeightPx, profile.authoredMarginPx)
