// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

/**
 * Connection state. 🔴 **The UI has to draw this** — without it the user cannot tell they have
 * been disconnected: the last frame stays on screen, so "disconnected" and "the picture stopped
 * moving" look identical.
 */
sealed interface VncConnectionState {
    /** Connecting. */
    data class Connecting(val detail: String) : VncConnectionState
    /** Connected. This is the only state in which the picture is current. */
    data object Connected : VncConnectionState
    /**
     * Failed, and **retrying on its own** (once a second). There is nothing for the user to do,
     * so this needs a notice rather than a button.
     */
    data class Retrying(val reason: String) : VncConnectionState
    /**
     * 🔴 A failure that retrying cannot help (an auth failure, say). **Automatic retry is
     * deliberately suppressed**, so a human pressing Retry is the only way forward.
     */
    data class Failed(val message: String) : VncConnectionState
}
