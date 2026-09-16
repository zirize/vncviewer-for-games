// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.settings

/**
 * The narrowest possible key/value sink, so that [SettingsCodec] stays free of `android.*` and can
 * be tested under JUnit with no device (the rule in `docs/architecture.md`).
 *
 * 🔑 **Everything is a String.** Not because it is tidy, but because a settings file that outlives
 * the code has to survive a type changing under it: `getInt` on a key that used to be a Float
 * throws `ClassCastException` and takes the whole app down at startup, where the user cannot even
 * reach the screen that would fix it. A String always parses or falls back.
 */
interface SettingsStore {
    /** null means "never written" — which is what tells the codec to leave the default alone. */
    fun read(key: String): String?

    /** Buffers a write. null removes the key. Nothing is durable until [commit]. */
    fun write(key: String, value: String?)

    /** Makes the buffered writes durable. */
    fun commit()
}

/**
 * A store that keeps nothing.
 *
 * 🔑 The fallback when a real one cannot be built — a Compose `@Preview` and a JUnit test both
 * construct the view with a context that has no working preferences. Settings then behave exactly
 * as they did before this existed (in-memory only) instead of crashing.
 */
object NoSettingsStore : SettingsStore {
    override fun read(key: String): String? = null
    override fun write(key: String, value: String?) {}
    override fun commit() {}
}
