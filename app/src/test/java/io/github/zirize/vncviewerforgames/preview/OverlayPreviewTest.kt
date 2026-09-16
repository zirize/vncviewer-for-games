// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.preview

import io.github.zirize.vncviewerforgames.overlay.OverlayProfileParser
import io.github.zirize.vncviewerforgames.overlay.OverlayProfileValidator
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renders one SVG per profile in `profiles/` into `app/build/preview/<id>.svg`.
 *
 * 🔑 **Why a *test* renders pictures** — to keep it to one command.
 * `./gradlew :app:testDebugUnitTest` both validates and produces the drawings, so nobody has to
 * remember "did I validate? did I look?" separately.
 * (`./gradlew :app:previewProfiles` calls the same thing and prints the paths.)
 */
class OverlayPreviewTest {

    @Test
    fun `render an svg for every profile`() {
        val out = File("build/preview").apply { mkdirs() }
        val files = File("../profiles").walkTopDown().filter { it.extension == "json" }.toList()
        assertTrue("profiles/ is empty", files.isNotEmpty())

        for (f in files) {
            val p = OverlayProfileParser.parse(f.readText())
            File(out, "${p.id}.svg").writeText(OverlayPreviewSvg.render(p))
        }

        // 🔑 The broken ones are drawn too: seeing why the validator refused is much faster than reading it.
        val brokenOut = File(out, "broken").apply { mkdirs() }
        File("src/test/resources/broken-profiles")
            .listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .forEach { f ->
                val p = OverlayProfileParser.parse(f.readText())
                val issues = OverlayProfileValidator.validate(p)
                File(brokenOut, f.name.removeSuffix(".json") + ".svg")
                    .writeText(OverlayPreviewSvg.render(p))
                File(brokenOut, f.name.removeSuffix(".json") + ".txt")
                    .writeText(OverlayProfileValidator.format(issues) + "\n")
            }

        assertTrue("no SVG was produced", out.listFiles().orEmpty().any { it.extension == "svg" })
    }
}
