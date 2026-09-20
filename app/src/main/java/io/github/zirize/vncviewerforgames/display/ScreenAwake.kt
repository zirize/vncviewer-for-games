// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.display

/**
 * Whether the device screen is held awake while the remote picture is on it.
 *
 * 🔴 **Watching a remote screen involves no hand movement.** Just looking at a game reads to the
 * system as "no interaction", so it turns the screen off, the activity stops, and the connection
 * drops. That is what the "the app dies" report on 2026-09-16 actually was: `screen_off_timeout`
 * was 15 seconds. Not a crash, not the low-memory killer — the screen went off.
 * ⇒ Which is why the default is [ALWAYS], and why turning it off has to say what it costs.
 *
 * 🔑 **Why this is a choice and not a switch.** The two failures are opposite and both real:
 * holding the screen on with nothing connected flattens a battery in a pocket, and letting it go
 * off mid-game ends the session. [WHILE_CONNECTED] is the one that has neither, at the price of a
 * screen that does time out while you are staring at the "retrying" banner.
 *
 * ⚠️ None of this can stop thermal protection from turning the screen off; the system wins that one.
 */
enum class ScreenAwakeMode {
    /** Held awake for as long as the app is in front. The behaviour before this was a setting. */
    ALWAYS,

    /**
     * Held awake only while the connection is live.
     * 🔑 A dropped connection then lets the screen time out, which stops the app retrying — that
     * is the point, not a side effect: nothing is being watched.
     */
    WHILE_CONNECTED,

    /** Never held. The system's screen timeout applies, and hitting it ends the session. */
    OFF;

    /** What `View.keepScreenOn` should be right now. [connected] is the live connection state. */
    fun keepAwake(connected: Boolean): Boolean = when (this) {
        ALWAYS -> true
        WHILE_CONNECTED -> connected
        OFF -> false
    }
}

/**
 * Device-side display settings — what the app does to the **phone**, as opposed to what it asks
 * the server for.
 *
 * 🔑 It is a class of its own, rather than a field on `VncConnectionConfig`, because that one is
 * the record of what was negotiated at connect time: anything added to it shows up in the
 * "applies from the next connection" pending bar, and this applies **now**.
 */
data class ScreenConfig(
    var awakeMode: ScreenAwakeMode = ScreenAwakeMode.ALWAYS,
)
