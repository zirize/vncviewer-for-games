// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 **This repository's promise is that a layout change can be proved correct without a device.**
 * This is the test that keeps it.
 *
 * 🔑 It reads the JSON **straight off disk** from `profiles/` - not the copy baked into the
 * assets, but the file the agent just edited. That is what catches a mistake before the build.
 */
class OverlayProfileTest {

    private fun profilesDir(): File {
        // 🔑 A unit test's working directory is the module folder (app/), so the repo root is one up.
        val d = File("../profiles")
        assertTrue("could not find profiles/: ${d.absolutePath}", d.isDirectory)
        return d
    }

    private fun load(name: String): OverlayProfile =
        OverlayProfileParser.parse(File(profilesDir(), name).readText())

    /**
     * 🔴 **The regression that matters: the layout people already have in their hands must not
     * move by a single pixel.**
     *
     * These values were frozen on 2026-09-17 by mechanically comparing against the layout as it
     * existed in Kotlin constants until then.
     * 🔑 So if this table disagrees, **the layout changed** - the test is not stale. If that was
     *    intended, update this too; if it was not, put `default.json` back.
     */
    @Test
    fun `default profile matches the layout that shipped`() {
        val p = load("default.json")
        assertEquals("authored margin", 240, p.authoredMarginPx)
        assertEquals("authored height", 1080, p.authoredHeightPx)
        assertEquals("corner radius", 20f, p.cornerRadiusPx, 0f)
        assertEquals("should parse with no warnings: ${p.warnings}", emptyList<String>(), p.warnings)

        val expected = listOf(
            //  id          label    shape                  panel  anchor     x    y    w    h
            Row("pageup",   "▲", ButtonShape.ROUND_RECT, "L", "T",   0,  51, 114, 114, "key:0xff55:TAP"),
            Row("pagedown", "▼", ButtonShape.ROUND_RECT, "L", "T",   0, 187, 114, 114, "key:0xff56:TAP"),
            Row("h",        "H",      ButtonShape.CIRCLE,     "L", "T", 126,  50, 114, 114, "key:0x68:TAP"),
            Row("ctrl",     "CTRL",   ButtonShape.CIRCLE,     "L", "T", 126, 187, 114, 114, "key:0xffe3:LATCH"),
            Row("enter",    "↵", ButtonShape.CIRCLE,     "L", "T", 126, 323, 114, 114, "key:0xff0d:TAP"),
            Row("dpad",     null,     ButtonShape.DPAD,       "L", "T",   2, 470, 236, 295, "key:0x0:HOLD"),
            Row("settings", null,     ButtonShape.ICON,       "L", "B",   5,   5, 107,  96, "ui:SETTINGS"),
            Row("mwup",     "MWUP",   ButtonShape.MOUSE,      "R", "T",  41,  35, 115, 114, "wheel:8x1"),
            Row("mwdn",     "MWDN",   ButtonShape.MOUSE,      "R", "T",  41, 227, 115, 114, "wheel:16x1"),
            Row("esc",      "ESC",    ButtonShape.ROUND_RECT, "R", "T",  41, 404, 105,  56, "key:0xff1b:TAP"),
            Row("f",        "F",      ButtonShape.CIRCLE,     "R", "B",  41, 191, 116, 116, "key:0x66:TAP"),
            Row("v",        "V",      ButtonShape.CIRCLE,     "R", "B",  41,  23, 116, 116, "key:0x76:TAP"),
        )
        assertEquals("button count", expected.size, p.buttons.size)
        for ((i, e) in expected.withIndex()) assertEquals("buttons[$i]", e, Row.of(p.buttons[i]))
    }

    /**
     * 🔴 **A label is not a keysym.** The label `F` is uppercase; the value sent is lowercase `f`
     * (0x66). In X11 an uppercase letter is the key with Shift held, so getting it wrong leaves
     * **the screen looking fine while only the game fails to respond.**
     * ⇒ There is no way to catch that by looking. The test catches it.
     */
    @Test
    fun `letter buttons send lowercase even though the label is uppercase`() {
        val p = load("default.json")
        for ((id, sym) in listOf("h" to 0x68, "f" to 0x66, "v" to 0x76)) {
            val b = p.buttons.first { it.id == id }
            assertEquals("$id label should be uppercase", id.uppercase(), b.label)
            assertEquals("$id should send the lowercase keysym", sym, (b.action as OverlayAction.Key).keySym)
        }
    }

    /** 🔴 A profile with no settings exit locks the user out. Every bundled profile must have one. */
    @Test
    fun `every bundled profile has a way into settings`() {
        val files = profilesDir().walkTopDown().filter { it.extension == "json" }.toList()
        assertTrue("there are no profiles at all", files.isNotEmpty())
        for (f in files) {
            val p = OverlayProfileParser.parse(f.readText())
            assertTrue("${f.name}: no button opens settings", hasSettingsEntry(p.buttons))
        }
    }

    /** A flat form that makes the comparison readable: on failure you can see which field is wrong. */
    private data class Row(
        val id: String, val label: String?, val shape: ButtonShape,
        val panel: String, val anchor: String,
        val x: Int, val y: Int, val w: Int, val h: Int, val action: String,
    ) {
        companion object {
            fun of(b: OverlayButton) = Row(
                b.id, b.label, b.shape,
                if (b.panel == OverlayPanel.LEFT) "L" else "R",
                if (b.anchor == OverlayAnchor.TOP) "T" else "B",
                b.x, b.y, b.w, b.h,
                when (val a = b.action) {
                    is OverlayAction.Key -> "key:0x${a.keySym.toString(16)}:${a.behavior}"
                    is OverlayAction.Wheel -> "wheel:${a.button}x${a.clicks}"
                    is OverlayAction.Mouse -> "mouse:${a.button}"
                    is OverlayAction.Ui -> "ui:${a.target}"
                },
            )
        }
    }
}
