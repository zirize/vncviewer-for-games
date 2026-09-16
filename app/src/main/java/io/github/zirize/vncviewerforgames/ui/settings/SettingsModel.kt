// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.ui.settings

import androidx.annotation.StringRes
import io.github.zirize.vncviewerforgames.R
import io.github.zirize.vncviewerforgames.conn.VncConnectionConfig

/**
 * Human-readable names for settings, used where the screen shows "current vs as-connected".
 *
 * 🔑 These return **string resource ids**, not text. The wording lives in res/values/ and
 * res/values-ko/, which is what lets the source stay English-only while Korean users keep the
 * Korean wording the app was written in.
 */
object SettingLabels {
    private val IDS = mapOf(
        "host" to R.string.label_host,
        "port" to R.string.label_port,
        "shared" to R.string.label_shared,
        "securityTypes" to R.string.label_security_types,
        "password" to R.string.label_password,
        "encodings" to R.string.label_encodings,
        "compressLevel" to R.string.label_compress_level,
        "qualityLevel" to R.string.label_quality_level,
        "subsampling" to R.string.label_subsampling,
        "pixelFormat" to R.string.label_pixel_format,
        "continuousUpdates" to R.string.label_continuous_updates,
        "desktopResize" to R.string.label_desktop_resize,
        "cursorShape" to R.string.label_cursor_shape,
        "clipboard" to R.string.label_clipboard,
    )

    /** null means there is no translated name; callers fall back to the raw key. */
    @StringRes
    fun of(key: String): Int? = IDS[key]
}

/**
 * The "next connection" settings that are **currently pending**.
 *
 * 🔴 **This is a count, not a sentence.** Showing "applies from the next connection" permanently
 * does not work: people read it and still believe the change took effect now — which is the exact
 * problem it was meant to solve.
 * ⇒ When it is empty, the bar is not drawn at all.
 */
data class PendingChanges(val keys: List<String>) {
    val isEmpty: Boolean get() = keys.isEmpty()
    val count: Int get() = keys.size

    companion object {
        fun of(now: VncConnectionConfig, connected: VncConnectionConfig?): PendingChanges =
            PendingChanges(connected?.let { now.diffRequiringReconnect(it) } ?: emptyList())
    }
}

/**
 * Options that ask once before being turned on.
 * 🔑 What they have in common: **the result cannot be undone from inside this app, or it affects
 * somebody else.**
 */
enum class RiskyOption(@StringRes val title: Int, @StringRes val why: Int) {
    SHARED_OFF(R.string.risky_shared_off_title, R.string.risky_shared_off_why),
    CLIPBOARD_ON(R.string.risky_clipboard_on_title, R.string.risky_clipboard_on_why),
}

/**
 * `qualityLevel` in words.
 * 🔴 **−1 does not mean "worse than 0", it means "the server will not use JPEG at all".**
 * Shown as a bare number it is guaranteed to be misread.
 */
@StringRes
fun qualityLabelRes(level: Int): Int = when {
    level == -1 -> R.string.quality_off
    level >= 7 -> R.string.quality_high
    level <= 3 -> R.string.quality_low
    else -> 0        // 0 = no label; show the number on its own
}

/**
 * `subsampling` in words.
 *
 * 🔑 **This is a colour-resolution control, not a quality control.** JPEG quality
 *    (`qualityLevel`) cuts sharpness (luma); this one leaves sharpness alone and reduces only
 *    colour information.
 * 🔴 The server unpacks a single `qualityLevel` into a (quality, subsampling) **pair** — q8 is
 *    92 with 4:4:4, q5 is 77 with 4:2:2. So unless this is set separately, lowering quality takes
 *    the colour down with it.
 * Measured 2026-09-16 (noise, 2340×1080): 4:4:4 gave 62.0 Mpx/s, 4:2:2 gave **75.7**.
 */
@StringRes
fun subsamplingLabelRes(level: Int): Int = when (level) {
    -2 -> R.string.chroma_auto_long
    -1 -> R.string.chroma_server_long
    0 -> R.string.chroma_444_long
    2 -> R.string.chroma_422_long
    1 -> R.string.chroma_420_long
    3 -> R.string.chroma_mono_long
    else -> 0
}

/**
 * The subsampling values the settings screen offers, **largest bytes first**.
 * 🔴 "Auto" and "Server" are **not the same thing**: auto means the app picks from the measured
 * load, server means it defers entirely.
 * Entries with a resource id of 0 show their literal label instead (the 4:x:x names need no
 * translation).
 */
val SUBSAMPLING_CHOICES: List<Triple<Int, Int, String>> = listOf(
    Triple(-2, R.string.chroma_auto, ""),
    Triple(-1, R.string.chroma_server, ""),
    Triple(0, 0, "4:4:4"),
    Triple(2, 0, "4:2:2"),
    Triple(1, 0, "4:2:0"),
    Triple(3, R.string.chroma_mono, ""),
)

/** `compressLevel` in words. */
@StringRes
fun compressLabelRes(level: Int): Int = when (level) {
    -1 -> R.string.compress_default
    0 -> R.string.compress_none
    9 -> R.string.compress_max
    else -> 0
}
