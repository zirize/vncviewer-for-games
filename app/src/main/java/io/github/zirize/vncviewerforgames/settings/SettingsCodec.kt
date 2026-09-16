// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.settings

import io.github.zirize.vncviewerforgames.conn.VncConnectionConfig
import io.github.zirize.vncviewerforgames.input.KeyConfig
import io.github.zirize.vncviewerforgames.input.PointerConfig
import io.github.zirize.vncviewerforgames.input.PointerMode

/**
 * Reads and writes the settings the user can change from the settings sheet.
 *
 * 🔴 **Without this the address is gone at every launch.** `VncConnectionConfig` starts from
 * `BuildConfig`, which is empty in a published build, so a user who typed their server in had to
 * type it again after every restart — and the only reason it was not noticed for longer is that a
 * development build has `vnc.dev.host` baked in from `local.properties`, so on a developer's device
 * it looked like it was being remembered.
 *
 * 🔑 **Only what the sheet can change is stored, deliberately.** Store a value the UI cannot reach
 * and its default is frozen on every device that ever ran the app: improving the default later
 * (as `subsampling` and `cursorShape` both were) would never reach an existing install, and the
 * user would have no control to undo it with. So `continuousUpdates`, `desktopResize`,
 * `securityTypes`, `encodings` and `pixelFormat` stay out until they have a control.
 *
 * 🔴 **`viewOnly` and `password` are never written.**
 *   - `viewOnly` restored at startup is an app where nothing responds and nothing on screen says
 *     why — the user reads that as broken, not as a setting they left on. It is a per-session
 *     switch by design.
 *   - The password comes from `local.properties`, which is untracked precisely so that no password
 *     is written down; copying it into a preferences file undoes that.
 *
 * 🔑 **What is saved wins over `vnc.dev.host` in `local.properties`.** It has to — otherwise the
 * user's own address loses to a build-time default. Pointing a device at a different server after
 * a save therefore means changing it in the sheet (or clearing the app's data), not editing
 * `local.properties`.
 */
object SettingsCodec {
    /**
     * 🔑 Bumped only when an existing key changes meaning. Settings written by a version this
     * build does not know are **left alone and not applied** — a wrong guess at a value is worse
     * than starting from defaults, and overwriting them would also destroy the settings of whoever
     * downgraded for a day.
     */
    const val VERSION = 1

    const val KEY_VERSION = "settings.version"

    // Connection
    const val KEY_HOST = "conn.host"
    const val KEY_PORT = "conn.port"
    const val KEY_SHARED = "conn.shared"

    // Display
    const val KEY_QUALITY = "conn.qualityLevel"
    const val KEY_SUBSAMPLING = "conn.subsampling"
    const val KEY_COMPRESS = "conn.compressLevel"
    const val KEY_CLIPBOARD = "conn.clipboard"
    const val KEY_CURSOR_SHAPE = "conn.cursorShape"

    // Pointer
    const val KEY_POINTER_MODE = "pointer.mode"
    const val KEY_TWO_FINGER_SCROLL = "pointer.twoFingerScroll"
    const val KEY_LONG_PRESS_RIGHT_CLICK = "pointer.longPressRightClick"
    const val KEY_SENSITIVITY = "pointer.sensitivity"
    const val KEY_SCROLL_SENSITIVITY = "pointer.scrollSensitivity"
    const val KEY_NATURAL_SCROLL = "pointer.naturalScroll"

    // Keys
    const val KEY_LATCH_ENABLED = "keys.latchEnabled"
    const val KEY_DOUBLE_TAP_MS = "keys.doubleTapMs"
    const val KEY_CLICK_CONSUMES_ONESHOT = "keys.mouseClickConsumesOneshot"

    /** The subsampling values that exist. Anything else in the store is ignored. */
    private val SUBSAMPLING_VALUES = setOf(-2, -1, 0, 1, 2, 3)

    /**
     * 🔴 **These ranges must match the sliders in `SettingsSheet`.** They are the same numbers on
     * purpose: a value outside the slider's range cannot be produced by the UI, so it can only
     * come from a hand-edited or corrupted file, and letting it through would give the user a
     * setting they cannot see or undo.
     */
    private val SENSITIVITY = 0.25f..5f
    private val SCROLL_SENSITIVITY = 0.25f..8f
    private val DOUBLE_TAP_MS = 150L..600L

    /**
     * Applies the saved settings on top of [conn], [pointer] and [keys], in place.
     *
     * Anything missing, unparseable or out of range leaves that one field at its default — one bad
     * value never costs the others. Returns true if anything at all was applied.
     */
    fun load(
        store: SettingsStore,
        conn: VncConnectionConfig,
        pointer: PointerConfig,
        keys: KeyConfig,
    ): Boolean {
        // 🔑 No version key = nothing has ever been saved. Not an error, and not a reason to write
        //    one either: a fresh install stays empty until the user changes something.
        val version = store.read(KEY_VERSION)?.trim()?.toIntOrNull() ?: return false
        if (version != VERSION) return false

        var applied = false
        fun mark() { applied = true }

        // 🔑 An empty host is kept as an empty host. The user clearing the field is a state the app
        //    handles (StartupFailureWatcher opens settings), not a value to second-guess.
        store.read(KEY_HOST)?.let { conn.host = it.trim(); mark() }
        readInt(store, KEY_PORT, 1..65535)?.let { conn.port = it; mark() }
        readBool(store, KEY_SHARED)?.let { conn.shared = it; mark() }

        readInt(store, KEY_QUALITY, -1..9)?.let { conn.qualityLevel = it; mark() }
        readInt(store, KEY_SUBSAMPLING)?.takeIf { it in SUBSAMPLING_VALUES }
            ?.let { conn.subsampling = it; mark() }
        readInt(store, KEY_COMPRESS, -1..9)?.let { conn.compressLevel = it; mark() }
        readBool(store, KEY_CLIPBOARD)?.let { conn.clipboard = it; mark() }
        readBool(store, KEY_CURSOR_SHAPE)?.let { conn.cursorShape = it; mark() }

        store.read(KEY_POINTER_MODE)
            ?.let { name -> PointerMode.entries.firstOrNull { it.name == name } }
            ?.let { pointer.mode = it; mark() }
        readBool(store, KEY_TWO_FINGER_SCROLL)?.let { pointer.twoFingerScroll = it; mark() }
        readBool(store, KEY_LONG_PRESS_RIGHT_CLICK)?.let { pointer.longPressRightClick = it; mark() }
        readFloat(store, KEY_SENSITIVITY, SENSITIVITY)?.let { pointer.sensitivity = it; mark() }
        readFloat(store, KEY_SCROLL_SENSITIVITY, SCROLL_SENSITIVITY)
            ?.let { pointer.scrollSensitivity = it; mark() }
        readBool(store, KEY_NATURAL_SCROLL)?.let { pointer.naturalScroll = it; mark() }

        readBool(store, KEY_LATCH_ENABLED)?.let { keys.latchEnabled = it; mark() }
        readLong(store, KEY_DOUBLE_TAP_MS, DOUBLE_TAP_MS)?.let { keys.doubleTapMs = it; mark() }
        readBool(store, KEY_CLICK_CONSUMES_ONESHOT)
            ?.let { keys.mouseClickConsumesOneshot = it; mark() }

        return applied
    }

    /** Writes every persisted setting and commits. Called on each change from the settings sheet. */
    fun save(
        store: SettingsStore,
        conn: VncConnectionConfig,
        pointer: PointerConfig,
        keys: KeyConfig,
    ) {
        store.write(KEY_VERSION, VERSION.toString())

        store.write(KEY_HOST, conn.host)
        store.write(KEY_PORT, conn.port.toString())
        store.write(KEY_SHARED, conn.shared.toString())

        store.write(KEY_QUALITY, conn.qualityLevel.toString())
        store.write(KEY_SUBSAMPLING, conn.subsampling.toString())
        store.write(KEY_COMPRESS, conn.compressLevel.toString())
        store.write(KEY_CLIPBOARD, conn.clipboard.toString())
        store.write(KEY_CURSOR_SHAPE, conn.cursorShape.toString())

        store.write(KEY_POINTER_MODE, pointer.mode.name)
        store.write(KEY_TWO_FINGER_SCROLL, pointer.twoFingerScroll.toString())
        store.write(KEY_LONG_PRESS_RIGHT_CLICK, pointer.longPressRightClick.toString())
        store.write(KEY_SENSITIVITY, pointer.sensitivity.toString())
        store.write(KEY_SCROLL_SENSITIVITY, pointer.scrollSensitivity.toString())
        store.write(KEY_NATURAL_SCROLL, pointer.naturalScroll.toString())

        store.write(KEY_LATCH_ENABLED, keys.latchEnabled.toString())
        store.write(KEY_DOUBLE_TAP_MS, keys.doubleTapMs.toString())
        store.write(KEY_CLICK_CONSUMES_ONESHOT, keys.mouseClickConsumesOneshot.toString())

        store.commit()
    }

    // ── Parsing ─────────────────────────────────────────────────────────
    // 🔑 Every one of these returns null for "not there, or not usable", and the caller then leaves
    //    the default in place. There is no third outcome, which is what keeps a damaged store from
    //    turning into a half-configured app.

    private fun readBool(store: SettingsStore, key: String): Boolean? =
        when (store.read(key)?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun readInt(store: SettingsStore, key: String, range: IntRange? = null): Int? =
        store.read(key)?.trim()?.toIntOrNull()?.takeIf { range == null || it in range }

    private fun readLong(store: SettingsStore, key: String, range: LongRange): Long? =
        store.read(key)?.trim()?.toLongOrNull()?.takeIf { it in range }

    private fun readFloat(store: SettingsStore, key: String, range: ClosedFloatingPointRange<Float>): Float? =
        store.read(key)?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it in range }
}
