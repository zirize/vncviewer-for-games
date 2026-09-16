// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import io.github.zirize.vncviewerforgames.overlay.OverlayProfileValidator.Severity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 **This is where the repository's acceptance criterion lives**: every deliberately broken
 * profile is caught.
 *
 * 🔑 The five are not imagined - they are **what an agent would plausibly do**, the natural result
 * of following a person's request literally: "remove that button", "make it the F key", "make the
 * buttons bigger", "move H to the left", and a typo.
 */
class OverlayProfileValidatorTest {

    private fun broken(name: String): OverlayProfile =
        OverlayProfileParser.parse(
            File("src/test/resources/broken-profiles/$name").readText())

    private fun errors(p: OverlayProfile) =
        OverlayProfileValidator.validate(p).filter { it.severity == Severity.ERROR }

    private fun codes(p: OverlayProfile) = errors(p).map { it.code }.toSet()

    // ── Everything we ship must pass ────────────────────────────────

    @Test
    fun `every profile we ship passes clean`() {
        val dir = File("../profiles")
        val files = dir.walkTopDown().filter { it.extension == "json" }.toList()
        assertTrue("profiles/ is empty", files.isNotEmpty())
        for (f in files) {
            val p = OverlayProfileParser.parse(f.readText())
            val issues = OverlayProfileValidator.validate(p)
            assertEquals(
                "${f.name} did not pass:\n${OverlayProfileValidator.format(issues)}",
                emptyList<OverlayProfileValidator.Issue>(), issues)
        }
    }

    // ── The five broken ones ────────────────────────────────────────

    /**
     * 🔴 **The important one.** "Get rid of the settings button" is something a person can ask
     * without thinking, and following it **leaves the user with no way back into their own app.**
     * It happened for real on 2026-09-16 and had to be reverted.
     */
    @Test
    fun `01 removing the settings button is refused`() {
        assertTrue("missing settings exit was not caught", "no-settings-exit" in codes(broken("01-no-settings-exit.json")))
    }

    /**
     * 🔴 **The kind you cannot catch by looking.** The screen is fine and **only the game fails
     * to respond.** That is why this check exists.
     */
    @Test
    fun `02 an uppercase keysym is caught even though the app still builds`() {
        val p = broken("02-uppercase-keysym.json")
        assertTrue("uppercase keysym was not caught", "uppercase-keysym" in codes(p))
        assertEquals("all three of F, H and V should be caught", 3, errors(p).count { it.code == "uppercase-keysym" })
    }

    @Test
    fun `03 buttons that spill out of the panel are caught`() {
        assertTrue("spilling out of the panel was not caught", "out-of-panel" in codes(broken("03-out-of-panel.json")))
    }

    @Test
    fun `04 buttons drawn on top of each other are caught`() {
        assertTrue("overlap was not caught", "overlap" in codes(broken("04-overlap.json")))
    }

    @Test
    fun `05 typos in shape and keysym are caught`() {
        // 🔑 The parser disables just that button and the validator raises it as an error.
        //    ⇒ The app never ends up with an empty screen, and whoever tries to build is stopped.
        assertTrue("the typos were not caught", "unreadable" in codes(broken("05-typos.json")))
    }

    /** 🔑 One more pass over all five: if any of them slips through, this is what fails. */
    @Test
    fun `all five broken profiles fail validation`() {
        val dir = File("src/test/resources/broken-profiles")
        val files = dir.walkTopDown().filter { it.extension == "json" }.toList().sortedBy { it.name }
        assertEquals("number of broken profiles", 5, files.size)
        for (f in files) {
            val p = OverlayProfileParser.parse(f.readText())
            assertTrue("${f.name} PASSED - the validator does not stop this mistake",
                errors(p).isNotEmpty())
        }
    }
}
