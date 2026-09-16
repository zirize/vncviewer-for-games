// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import io.github.zirize.vncviewerforgames.input.VncKeySym

/**
 * Decides whether one profile is fit to build. **On a plain JVM, with no device.**
 *
 * 🔴 **This file is the promise this repository makes.** The thing editing layouts is usually an
 * agent, so something has to bridge "I changed it" and "I changed it correctly". If that can only
 * be established on hardware, an agent can *edit* but never *verify*.
 *
 * 🔑 **What it checks came from failures that actually happened**, not from a list of things that
 * could go wrong:
 * - Losing the settings exit — happened here on 2026-09-16; the only door back into the app's own
 *   settings disappeared and the change had to be reverted.
 * - Label/keysym confusion — when it is wrong **the screen looks fine and only the game fails to
 *   respond.** There is no way to see it by looking.
 * - Out of panel, overlapping — the numbers look plausible while fingers miss, or hit the wrong thing.
 */
object OverlayProfileValidator {

    enum class Severity {
        /** 🔴 Do not build this. */
        ERROR,
        /** ⚠️ It will run, but it may not be what was meant. */
        WARNING,
    }

    data class Issue(val severity: Severity, val code: String, val message: String)

    /** One [Severity.ERROR] means the profile does not pass. */
    fun validate(profile: OverlayProfile): List<Issue> {
        val out = mutableListOf<Issue>()

        // ── 1. It must not lock itself ─────────────────────────────────────
        // 🔴 The most important rule. With no button that opens settings, the person holding the
        //    phone cannot undo anything. "Please remove that button" gets refused here.
        if (!hasSettingsEntry(profile.buttons.filter { it.enabled })) {
            out += Issue(Severity.ERROR, "no-settings-exit",
                "No button opens settings. The user would have no way to undo this layout from " +
                "inside the app - keep one button with {\"type\":\"ui\",\"command\":\"settings\"}.")
        }

        // ── 2. Anything the parser had to read leniently ───────────────────
        for (w in profile.warnings) {
            out += Issue(Severity.ERROR, "unreadable", "something could not be read - $w")
        }

        // ── 3. Duplicate ids ───────────────────────────────────────────────
        profile.buttons.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            out += Issue(Severity.ERROR, "duplicate-id", "two buttons share the id `$it`")
        }

        // ── 4. Spilling out of the panel ───────────────────────────────────
        // 🔑 x is measured from the outer edge, so x+w past the margin means the button sticks out
        //    over the remote picture.
        val margin = profile.authoredMarginPx
        val height = profile.authoredHeightPx
        for (b in profile.buttons) {
            if (b.w <= 0 || b.h <= 0) {
                out += Issue(Severity.ERROR, "bad-size", "`${b.id}`: size is zero or negative (${b.w}x${b.h})")
                continue
            }
            if (b.x < 0 || b.y < 0) {
                out += Issue(Severity.ERROR, "out-of-panel", "`${b.id}`: negative coordinates (${b.x},${b.y})")
            }
            if (b.x + b.w > margin) {
                out += Issue(Severity.ERROR, "out-of-panel",
                    "`${b.id}`: spills out of the panel - x+w = ${b.x + b.w} > margin $margin. " +
                    "It would sit over the remote screen and cover what the user is doing.")
            }
            if (b.y + b.h > height) {
                out += Issue(Severity.ERROR, "out-of-panel",
                    "`${b.id}`: runs off the bottom - y+h = ${b.y + b.h} > height $height")
            }
        }

        // ── 5. Drawn on top of each other ──────────────────────────────────
        // 🔑 *Touch* areas are expanded to 48dp and are supposed to overlap; where they do, the
        //    nearer centre wins. But two **drawn** rectangles overlapping is a layout mistake, and
        //    it looks like one too.
        for (panel in OverlayPanel.entries) {
            val inPanel = profile.buttons.filter { it.panel == panel }
            for (i in inPanel.indices) for (j in i + 1 until inPanel.size) {
                val a = inPanel[i]; val b = inPanel[j]
                if (a.anchor != b.anchor) continue   // different bases; needs the screen height to say
                val ox = maxOf(0, minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x))
                val oy = maxOf(0, minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y))
                if (ox > 0 && oy > 0) {
                    out += Issue(Severity.ERROR, "overlap",
                        "`${a.id}` and `${b.id}` are drawn on top of each other (${ox}x${oy}px). " +
                        "Which one ends up on top is not defined.")
                }
            }
        }

        // ── 6. Label / keysym confusion ────────────────────────────────────
        // 🔴 **The easiest mistake to make here.** Label a button `F`, write `F` as the keysym, and
        //    X11 sends *F with Shift held*. A game's F key is lowercase f (0x66).
        //    🔑 It compiles, it draws, the screen is fine - **only the game does not react.**
        for (b in profile.buttons) {
            val a = b.action as? OverlayAction.Key ?: continue
            val ch = a.keySym.toChar()
            if (a.keySym in 'A'.code..'Z'.code) {
                out += Issue(Severity.ERROR, "uppercase-keysym",
                    "`${b.id}`: the keysym is uppercase `$ch` (0x${a.keySym.toString(16)}). " +
                    "In X11 an uppercase letter is the key with Shift held, which a game reads " +
                    "differently - write lowercase `${ch.lowercaseChar()}`. " +
                    "(The label can stay uppercase; that part is correct.)")
            }
            if (a.behavior == KeyBehavior.LATCH && a.keySym !in VncKeySym.MODIFIERS) {
                out += Issue(Severity.WARNING, "latch-on-non-modifier",
                    "`${b.id}`: latch only means anything on a modifier - read as tap.")
            }
        }

        // ── 7. Too small to find ───────────────────────────────────────────
        // ⚠️ The touch area is grown to 48dp anyway, but something drawn this small is not *visible*.
        for (b in profile.buttons) {
            if (b.w in 1..23 || b.h in 1..23) {
                out += Issue(Severity.WARNING, "tiny",
                    "`${b.id}`: ${b.w}x${b.h}px is hard to see (the authored margin is ${margin}px).")
            }
        }

        return out
    }

    /** One line per issue, for test failures and for the preview output. */
    fun format(issues: List<Issue>): String =
        if (issues.isEmpty()) "no problems"
        else issues.joinToString("\n") { "  ${if (it.severity == Severity.ERROR) "ERROR" else "warn "} [${it.code}] ${it.message}" }
}
