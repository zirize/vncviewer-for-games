// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * [SettingsStore] on top of `SharedPreferences`.
 *
 * 🔑 Writes are buffered into one editor and go out with `apply()`, off the caller's thread. The
 * settings sheet saves on **every keystroke** while the address is being typed, and `commit()`
 * there would be a disk write per character on the UI thread.
 *
 * 🔴 Reading is wrapped: a preferences file that has been damaged (or written by an older build
 * with a different type under the same key) throws, and it throws at **startup**, before the user
 * can reach any screen that would let them fix it. A lost setting is recoverable; a crash loop is
 * not.
 */
class SharedPrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private var pending: SharedPreferences.Editor? = null

    override fun read(key: String): String? =
        try {
            prefs.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "ignoring unreadable setting '$key'", e)
            null
        }

    override fun write(key: String, value: String?) {
        val editor = pending ?: prefs.edit().also { pending = it }
        if (value == null) editor.remove(key) else editor.putString(key, value)
    }

    override fun commit() {
        pending?.apply()
        pending = null
    }

    companion object {
        private const val TAG = "VncSettings"

        /** The preferences file name. 🔴 Changing it loses every user's settings. */
        const val NAME = "vnc_settings"

        /**
         * Builds a store, or [NoSettingsStore] if this context cannot provide preferences.
         *
         * 🔑 The caller is a `View` constructor, which also runs under a Compose `@Preview` and in
         * unit tests. Settings that are not remembered is a far smaller failure than a view that
         * cannot be constructed.
         */
        fun createOrNull(context: Context): SettingsStore =
            try {
                SharedPrefsSettingsStore(context)
            } catch (e: Exception) {
                Log.w(TAG, "no settings storage available; settings will not be remembered", e)
                NoSettingsStore
            }
    }
}
