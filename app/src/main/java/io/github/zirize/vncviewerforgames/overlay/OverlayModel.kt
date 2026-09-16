// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import io.github.zirize.vncviewerforgames.input.VncButton
import io.github.zirize.vncviewerforgames.input.VncKeySym

/**
 * What a button looks like.
 *
 * 🔴 **There is no "pill" shape on this screen.** The corner radius is a fixed **20px** (measured:
 * ▲ 21.4, MWUP 22.2, ESC 19.0, F 20.1), so even `ESC` at 105×56 is not a pill. Make the radius
 * proportional to the size instead and small buttons turn into circles while long ones turn into
 * pills — the panel stops looking like one family.
 *
 * 🔑 [MOUSE] is a rounded body with two small shapes on top: a mouse seen from above. The two
 * shapes are decoration only and are **not** part of the touch area — only the body is pressable.
 */
enum class ButtonShape { ROUND_RECT, CIRCLE, MOUSE, DPAD, ICON }

/** Which margin the button sits in. [x] is measured from the **outer** screen edge. */
enum class OverlayPanel { LEFT, RIGHT }

/** Vertical reference point. 🔑 Keeps buttons on screen when the screen *height* differs. */
enum class OverlayAnchor { TOP, BOTTOM }

/** How a key is held. */
enum class KeyBehavior {
    /** Press and release. */
    TAP,
    /** Down only while a finger is on it (D-pad, movement keys). */
    HOLD,
    /** Tap to arm it and leave it armed (modifiers). OFF → ONESHOT → LOCK. */
    LATCH,
}

/**
 * What a button that sends nothing to the server does instead.
 *
 * 🔑 **`SETTINGS` is the only one.** An edit mode was ruled out of scope (2026-09-15), and the
 * keyboard (IME) toggle lost its place entirely once the bottom-left slot became settings.
 *
 * 🚫 Leaving unused values in the enum makes them read as features that exist. Add one back the
 * day it is needed; it is one line.
 */
enum class UiTarget { SETTINGS }

/**
 * What happens when a button is pressed.
 *
 * 🔴 [Ui] lives **inside** the keymap, not beside it, because the settings button is a button like
 * any other. Keep it outside and it becomes the one button a layout cannot move — two sets of
 * rules for the same screen.
 */
sealed interface OverlayAction {
    data class Key(val keySym: Int, val behavior: KeyBehavior) : OverlayAction
    data class Wheel(val button: Int, val clicks: Int = 1) : OverlayAction
    data class Mouse(val button: Int) : OverlayAction
    data class Ui(val target: UiTarget) : OverlayAction
}

/**
 * One button. Coordinates are **authored pixels** (a 240px margin on a 1080px-high screen).
 *
 * [x] is measured from the **outer edge** the panel is attached to, so buttons stay glued to the
 * screen edge when the margin changes width instead of drifting toward the middle.
 * [y] is measured from the top when [anchor] is TOP and **from the bottom** when it is BOTTOM.
 */
data class OverlayButton(
    val id: String,
    val label: String?,
    val shape: ButtonShape,
    val panel: OverlayPanel,
    val anchor: OverlayAnchor,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val action: OverlayAction,
    /**
     * 🔴 false = **still drawn, but dimmed and inert.** A button ends up here when its `action`
     * could not be read out of the profile (see the format reference, "When a profile is wrong").
     *
     * 🔑 **Why dim it rather than hide it** — a button that vanishes reads as "I deleted it by
     * accident". A dimmed one reads as "something is wrong here", which is the truth.
     */
    val enabled: Boolean = true,
)

/*
 * 🔑 **The default layout is not in this file.** It lives in `profiles/` at the repository root
 *    (read by [OverlayProfileParser]; wired into the APK by `assets.srcDir` in
 *    app/build.gradle.kts).
 *
 * 🔴 **Why it was taken out of Kotlin** — the point of this repository is that when someone asks
 *    for a layout that fits their hands, an *agent* produces it. If the layout is a Kotlin
 *    constant the agent has to edit code, and checking the result needs a device. As JSON,
 *    neither is true.
 *
 *    Moved on 2026-09-17, and the values were compared against the pre-move ones mechanically:
 *    not one pixel differs. `OverlayProfileTest` freezes that comparison so it stays true.
 */
