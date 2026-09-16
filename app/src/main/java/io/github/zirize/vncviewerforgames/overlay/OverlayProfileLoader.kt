// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import android.content.Context
import android.util.Log

/**
 * Reads a profile out of the app's own assets.
 *
 * 🔑 **Why an asset, i.e. why not runtime editing** — what an agent produces for someone is a
 * *build*, not a settings screen. `profiles/` is baked into the APK as-is, so changing the layout
 * means editing the JSON and rebuilding, and the app needs no editor UI.
 * The wiring is one line in `app/build.gradle.kts`: `assets.srcDir(rootProject.file("profiles"))`.
 *
 * 🔴 **Nothing throws out of here.** If the app died because a profile was unreadable, there would
 * be no way to get in and fix it. Whatever happens, it opens — with
 * [OverlayProfileParser.RESCUE] if it has to.
 */
object OverlayProfileLoader {

    fun load(context: Context, assetName: String): OverlayProfile {
        return try {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val p = OverlayProfileParser.parse(text)
            for (w in p.warnings) Log.w(TAG, "profile '${p.id}': $w")
            Log.i(TAG, "profile '${p.id}' - ${p.buttons.size} buttons" +
                if (p.warnings.isEmpty()) "" else " (${p.warnings.size} warnings)")
            p
        } catch (e: Exception) {
            // 🔴 Getting here means something that should have been caught at build time was not.
            //    profiles/ is validated by unit tests and by checkSelectedProfile; they come first.
            Log.e(TAG, "could not read profile '$assetName' - opening the rescue profile", e)
            OverlayProfileParser.RESCUE
        }
    }

    private const val TAG = "OverlayProfile"
}
