// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

/**
 * **If the very first connection fails, settings opens itself.**
 *
 * 🔴 **Why this is needed** — the overlay's settings button is the only route to fixing the server
 * address, and when the address is wrong the user has no idea that is what to do. The banner only
 * says "retrying". ⇒ After a few futile attempts, hand them the screen they can fix it on.
 *
 * 🔑 **Three rules, all of them about not getting in the way:**
 * 1. **Never again once a connection has succeeded** ([everConnected]). If the server blips
 *    mid-game, covering the screen with settings hides the game for no reason — there is nothing
 *    to fix. This only helps when it never worked in the first place.
 * 2. **At most once per session** ([fired]). Closing it means the user said "understood". Keep
 *    reopening and the app becomes unusable: the code opens faster than a hand can close.
 * 3. **[VncConnectionState.Failed] opens immediately** — for a failure that retrying cannot help,
 *    like a bad password, there is nothing to wait for, and waiting only risks a server lockout.
 *
 * 🔑 [onState] must be called **once per emission**, because consecutive identical values still
 * have to be counted. (`Retrying("...")` is a data class, so repeated failures arrive as the *same
 * value*; hanging this off Compose's `LaunchedEffect(state)` would stop firing after the first one
 * and it would never open.)
 *
 * 🔴 **Emissions and attempts are not the same thing.** One failure arrives as `Retrying(Error…)`
 * **and** `Retrying(Disconnected)` — two emissions, confirmed from device logs on 2026-09-16.
 * Counting emissions makes "3 attempts" really 1.5, which does not line up with the one-second
 * retry interval, and then the logs lie to you.
 * ⇒ Only the **first** failure after [VncConnectionState.Connecting] is counted, so one attempt
 * counts once.
 */
class StartupFailureWatcher(
    /** How many futile attempts before opening. 🔑 The retry interval is one second, so 3 is about 3 seconds. */
    private val attemptsBeforeOpen: Int = 3,
) {
    private var everConnected = false
    private var fired = false
    private var attempts = 0
    /** Was the previous emission already a failure? 🔑 Folds the two emissions of one attempt into one. */
    private var inFailure = false

    /** How many startup failures have been counted. For tests and logging. */
    val failedAttempts: Int get() = attempts

    /**
     * Called on every state emission.
     * @return **true means open settings.** At most one true per session.
     */
    fun onState(state: VncConnectionState): Boolean {
        when (state) {
            is VncConnectionState.Connected -> {
                everConnected = true
                attempts = 0
                inFailure = false
                return false
            }
            is VncConnectionState.Connecting -> {
                inFailure = false      // 🔑 A new attempt started - the next failure counts afresh
                return false
            }
            is VncConnectionState.Retrying -> {
                if (everConnected || fired) return false
                if (inFailure) return false        // second emission of the same attempt - not counted
                inFailure = true
                attempts++
                if (attempts < attemptsBeforeOpen) return false
            }
            is VncConnectionState.Failed -> {
                // 🔑 Retrying cannot help this one - open immediately rather than counting.
                if (everConnected || fired) return false
            }
        }
        fired = true
        return true
    }
}
