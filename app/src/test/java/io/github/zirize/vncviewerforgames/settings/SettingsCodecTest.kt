// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.settings

import io.github.zirize.vncviewerforgames.conn.VncConnectionConfig
import io.github.zirize.vncviewerforgames.display.ScreenAwakeMode
import io.github.zirize.vncviewerforgames.display.ScreenConfig
import io.github.zirize.vncviewerforgames.input.KeyConfig
import io.github.zirize.vncviewerforgames.input.PointerConfig
import io.github.zirize.vncviewerforgames.input.PointerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory [SettingsStore]; `commit()` is what makes a write visible, as on a device. */
private class FakeStore(initial: Map<String, String> = emptyMap()) : SettingsStore {
    val values = initial.toMutableMap()
    private val pending = mutableMapOf<String, String?>()
    var commits = 0
        private set

    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) { pending[key] = value }
    override fun commit() {
        commits++
        pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        pending.clear()
    }
}

class SettingsCodecTest {

    private fun saved(
        build: (VncConnectionConfig, PointerConfig, KeyConfig, ScreenConfig) -> Unit,
    ): FakeStore {
        val store = FakeStore()
        val conn = VncConnectionConfig()
        val pointer = PointerConfig()
        val keys = KeyConfig()
        val screen = ScreenConfig()
        build(conn, pointer, keys, screen)
        SettingsCodec.save(store, conn, pointer, keys, screen)
        return store
    }

    // ── The bug this exists for ─────────────────────────────────────────

    @Test
    fun `the address survives a round trip`() {
        val store = saved { conn, _, _, _ -> conn.host = "192.0.2.10"; conn.port = 5901 }

        val fresh = VncConnectionConfig()
        assertTrue(SettingsCodec.load(store, fresh, PointerConfig(), KeyConfig(), ScreenConfig()))
        assertEquals("192.0.2.10", fresh.host)
        assertEquals(5901, fresh.port)
    }

    @Test
    fun `nothing saved leaves every default alone`() {
        val conn = VncConnectionConfig()
        val pointer = PointerConfig()
        val keys = KeyConfig()
        val screen = ScreenConfig()

        assertFalse(SettingsCodec.load(FakeStore(), conn, pointer, keys, screen))

        assertEquals(VncConnectionConfig().host, conn.host)
        assertEquals(VncConnectionConfig().port, conn.port)
        assertEquals(VncConnectionConfig().subsampling, conn.subsampling)
        assertEquals(PointerConfig(), pointer)
        assertEquals(KeyConfig(), keys)
        assertEquals(ScreenConfig(), screen)
    }

    @Test
    fun `an emptied host is kept, not treated as absent`() {
        val store = saved { conn, _, _, _ -> conn.host = "" }

        val conn = VncConnectionConfig()
        conn.host = "192.0.2.10"
        SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig())
        assertEquals("", conn.host)
    }

    @Test
    fun `whitespace around a typed address is dropped`() {
        val store = FakeStore(mapOf(
            SettingsCodec.KEY_VERSION to "1",
            SettingsCodec.KEY_HOST to "  192.0.2.10  ",
            SettingsCodec.KEY_PORT to " 5901 ",
        ))

        val conn = VncConnectionConfig()
        SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig())
        assertEquals("192.0.2.10", conn.host)
        assertEquals(5901, conn.port)
    }

    // ── Everything the sheet can change ─────────────────────────────────

    @Test
    fun `every setting the sheet offers makes the round trip`() {
        val store = saved { conn, pointer, keys, screen ->
            conn.host = "198.51.100.7"
            conn.port = 5905
            conn.shared = false
            conn.qualityLevel = 3
            conn.subsampling = 2
            conn.compressLevel = 9
            conn.clipboard = true
            conn.cursorShape = false
            pointer.mode = PointerMode.ABSOLUTE
            pointer.twoFingerScroll = false
            pointer.longPressRightClick = false
            pointer.sensitivity = 2.5f
            pointer.scrollSensitivity = 4f
            pointer.naturalScroll = true
            keys.latchEnabled = true
            keys.doubleTapMs = 450
            keys.mouseClickConsumesOneshot = true
            screen.awakeMode = ScreenAwakeMode.WHILE_CONNECTED
        }

        val conn = VncConnectionConfig()
        val pointer = PointerConfig()
        val keys = KeyConfig()
        val screen = ScreenConfig()
        assertTrue(SettingsCodec.load(store, conn, pointer, keys, screen))

        assertEquals("198.51.100.7", conn.host)
        assertEquals(5905, conn.port)
        assertFalse(conn.shared)
        assertEquals(3, conn.qualityLevel)
        assertEquals(2, conn.subsampling)
        assertEquals(9, conn.compressLevel)
        assertTrue(conn.clipboard)
        assertFalse(conn.cursorShape)
        assertEquals(PointerMode.ABSOLUTE, pointer.mode)
        assertFalse(pointer.twoFingerScroll)
        assertFalse(pointer.longPressRightClick)
        assertEquals(2.5f, pointer.sensitivity, 0.0001f)
        assertEquals(4f, pointer.scrollSensitivity, 0.0001f)
        assertTrue(pointer.naturalScroll)
        assertTrue(keys.latchEnabled)
        assertEquals(450L, keys.doubleTapMs)
        assertTrue(keys.mouseClickConsumesOneshot)
        assertEquals(ScreenAwakeMode.WHILE_CONNECTED, screen.awakeMode)
    }

    /**
     * 🔴 **The one whose default must be restorable.** Holding the screen awake is the default
     * because the alternative ends the session by itself (2026-09-16), so OFF has to come back
     * from the store as OFF — silently falling back to the default would look like the setting
     * does nothing.
     */
    @Test
    fun `screen off survives a round trip`() {
        val store = saved { _, _, _, screen -> screen.awakeMode = ScreenAwakeMode.OFF }

        val screen = ScreenConfig()
        assertTrue(SettingsCodec.load(store, VncConnectionConfig(), PointerConfig(), KeyConfig(), screen))
        assertEquals(ScreenAwakeMode.OFF, screen.awakeMode)
    }

    /** A mode written by a newer build leaves the default in place rather than being guessed at. */
    @Test
    fun `an unknown screen awake mode is ignored`() {
        val store = FakeStore(mapOf(
            SettingsCodec.KEY_VERSION to "1",
            SettingsCodec.KEY_SCREEN_AWAKE to "WHILE_CHARGING",
        ))

        val screen = ScreenConfig()
        screen.awakeMode = ScreenAwakeMode.OFF
        SettingsCodec.load(store, VncConnectionConfig(), PointerConfig(), KeyConfig(), screen)
        assertEquals(ScreenAwakeMode.OFF, screen.awakeMode)
    }

    /**
     * 🔴 The adaptive value is **−2, not 0**. Stored as a plain number it is the one value most
     * likely to be mangled by a range check written from the slider's bounds.
     */
    @Test
    fun `adaptive subsampling survives`() {
        val store = saved { conn, _, _, _ -> conn.subsampling = VncConnectionConfig.SUBSAMP_ADAPTIVE }

        val conn = VncConnectionConfig()
        conn.subsampling = 0
        SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig())
        assertEquals(VncConnectionConfig.SUBSAMP_ADAPTIVE, conn.subsampling)
    }

    /** JPEG off is −1, and −1 is not "out of range". */
    @Test
    fun `quality and compression off survive`() {
        val store = saved { conn, _, _, _ -> conn.qualityLevel = -1; conn.compressLevel = -1 }

        val conn = VncConnectionConfig()
        SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig())
        assertEquals(-1, conn.qualityLevel)
        assertEquals(-1, conn.compressLevel)
    }

    /**
     * 🔴 **By name, not by number.** Stored as an ordinal, inserting a mode into the middle of the
     * enum would silently change what every existing install means by its saved value.
     */
    @Test
    fun `the screen awake mode is stored by name`() {
        val store = saved { _, _, _, screen -> screen.awakeMode = ScreenAwakeMode.WHILE_CONNECTED }

        assertEquals("WHILE_CONNECTED", store.values[SettingsCodec.KEY_SCREEN_AWAKE])
    }

    // ── What must never be written ──────────────────────────────────────

    /**
     * 🔴 View-only restored at startup is an app where nothing responds and nothing says why.
     * It is a per-session switch.
     */
    @Test
    fun `view only is never stored`() {
        val store = saved { conn, _, _, _ -> conn.viewOnly = true }

        assertNull(store.values.keys.firstOrNull { it.contains("viewOnly", ignoreCase = true) })

        val conn = VncConnectionConfig()
        SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig())
        assertFalse(conn.viewOnly)
    }

    /** The password lives in an untracked file precisely so it is not written down. */
    @Test
    fun `the password is never stored`() {
        val store = saved { conn, _, _, _ -> conn.password = "hunter2" }

        assertTrue(store.values.values.none { it == "hunter2" })
        assertNull(store.values.keys.firstOrNull { it.contains("password", ignoreCase = true) })
    }

    /**
     * 🔑 Settings with no control must stay unstored, or their default freezes on every device
     * that ever ran the app and a later improvement never arrives.
     */
    @Test
    fun `settings with no control are not stored`() {
        val store = saved { _, _, _, _ -> }

        listOf("continuousUpdates", "desktopResize", "securityTypes", "encodings", "pixelFormat")
            .forEach { name ->
                assertNull("$name must not be stored",
                    store.values.keys.firstOrNull { it.contains(name, ignoreCase = true) })
            }
    }

    // ── A damaged store must not cost more than the one value ───────────

    @Test
    fun `an unparseable value leaves that field alone and keeps the others`() {
        val store = FakeStore(mapOf(
            SettingsCodec.KEY_VERSION to "1",
            SettingsCodec.KEY_HOST to "192.0.2.10",
            SettingsCodec.KEY_PORT to "not a number",
            SettingsCodec.KEY_SHARED to "yes please",
            SettingsCodec.KEY_QUALITY to "",
        ))

        val conn = VncConnectionConfig()
        assertTrue(SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig()))
        assertEquals("192.0.2.10", conn.host)
        assertEquals(VncConnectionConfig().port, conn.port)
        assertEquals(VncConnectionConfig().shared, conn.shared)
        assertEquals(VncConnectionConfig().qualityLevel, conn.qualityLevel)
    }

    @Test
    fun `out of range values are ignored rather than clamped in`() {
        val store = FakeStore(mapOf(
            SettingsCodec.KEY_VERSION to "1",
            SettingsCodec.KEY_PORT to "70000",
            SettingsCodec.KEY_QUALITY to "42",
            SettingsCodec.KEY_SUBSAMPLING to "7",
            SettingsCodec.KEY_SENSITIVITY to "900",
            SettingsCodec.KEY_SCROLL_SENSITIVITY to "NaN",
            SettingsCodec.KEY_DOUBLE_TAP_MS to "5",
        ))

        val conn = VncConnectionConfig()
        val pointer = PointerConfig()
        val keys = KeyConfig()
        SettingsCodec.load(store, conn, pointer, keys, ScreenConfig())

        assertEquals(VncConnectionConfig().port, conn.port)
        assertEquals(VncConnectionConfig().qualityLevel, conn.qualityLevel)
        assertEquals(VncConnectionConfig().subsampling, conn.subsampling)
        assertEquals(PointerConfig().sensitivity, pointer.sensitivity, 0.0001f)
        assertEquals(PointerConfig().scrollSensitivity, pointer.scrollSensitivity, 0.0001f)
        assertEquals(KeyConfig().doubleTapMs, keys.doubleTapMs)
    }

    @Test
    fun `an unknown pointer mode is ignored`() {
        val store = FakeStore(mapOf(
            SettingsCodec.KEY_VERSION to "1",
            SettingsCodec.KEY_POINTER_MODE to "JOYSTICK",
        ))

        val pointer = PointerConfig()
        SettingsCodec.load(store, VncConnectionConfig(), pointer, KeyConfig(), ScreenConfig())
        assertEquals(PointerConfig().mode, pointer.mode)
    }

    /** 🔴 A file written by a build that knows more than this one is left untouched. */
    @Test
    fun `settings from a newer version are not applied`() {
        val store = FakeStore(mapOf(
            SettingsCodec.KEY_VERSION to (SettingsCodec.VERSION + 1).toString(),
            SettingsCodec.KEY_HOST to "192.0.2.10",
        ))

        val conn = VncConnectionConfig()
        assertFalse(SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig()))
        assertEquals(VncConnectionConfig().host, conn.host)
    }

    @Test
    fun `values without a version key are not applied`() {
        val store = FakeStore(mapOf(SettingsCodec.KEY_HOST to "192.0.2.10"))

        val conn = VncConnectionConfig()
        assertFalse(SettingsCodec.load(store, conn, PointerConfig(), KeyConfig(), ScreenConfig()))
        assertEquals(VncConnectionConfig().host, conn.host)
    }

    // ── Storage behaviour ───────────────────────────────────────────────

    @Test
    fun `saving stamps the version and commits exactly once`() {
        val store = saved { conn, _, _, _ -> conn.host = "192.0.2.10" }

        assertEquals(SettingsCodec.VERSION.toString(), store.values[SettingsCodec.KEY_VERSION])
        assertEquals(1, store.commits)
    }

    /** Typing an address saves per keystroke; the last write is the one that stands. */
    @Test
    fun `repeated saves keep the last value`() {
        val store = FakeStore()
        val conn = VncConnectionConfig()
        val pointer = PointerConfig()
        val keys = KeyConfig()

        "192.0.2.10".forEachIndexed { i, _ ->
            conn.host = "192.0.2.10".substring(0, i + 1)
            SettingsCodec.save(store, conn, pointer, keys, ScreenConfig())
        }

        val fresh = VncConnectionConfig()
        SettingsCodec.load(store, fresh, PointerConfig(), KeyConfig(), ScreenConfig())
        assertEquals("192.0.2.10", fresh.host)
    }

    @Test
    fun `the store that keeps nothing is safe to use`() {
        NoSettingsStore.write("k", "v")
        NoSettingsStore.commit()
        assertNull(NoSettingsStore.read("k"))

        val conn = VncConnectionConfig()
        conn.host = "192.0.2.10"
        SettingsCodec.save(NoSettingsStore, conn, PointerConfig(), KeyConfig(), ScreenConfig())
        assertFalse(SettingsCodec.load(NoSettingsStore, conn, PointerConfig(), KeyConfig(), ScreenConfig()))
        assertEquals("192.0.2.10", conn.host)
    }
}
