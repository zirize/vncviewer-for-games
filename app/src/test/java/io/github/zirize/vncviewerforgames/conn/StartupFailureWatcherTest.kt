// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind "failed from the start, so open settings".
 *
 * 🔑 What these tests protect is **not opening**, not opening. Open it at the wrong moment and it
 * covers the screen mid-game, which reads as an unusable app long before it reads as a bug.
 */
class StartupFailureWatcherTest {

    private fun retry() = VncConnectionState.Retrying("Error: connection refused")

    @Test fun `it opens on the third futile attempt, not before`() {
        val w = StartupFailureWatcher(attemptsBeforeOpen = 3)
        assertFalse(w.onState(VncConnectionState.Connecting("")))
        assertFalse(w.onState(retry()))
        assertFalse(w.onState(VncConnectionState.Connecting("")))
        assertFalse(w.onState(retry()))
        assertFalse(w.onState(VncConnectionState.Connecting("")))
        assertTrue(w.onState(retry()))
    }

    @Test fun `identical consecutive failures are still counted`() {
        // 🔴 Retrying is a data class, so repeated failures compare equal. Deduplicating by value
        //    would mean it never opens at all.
        val w = StartupFailureWatcher(attemptsBeforeOpen = 2)
        val same = retry()
        assertFalse(w.onState(same))
        assertFalse(w.onState(VncConnectionState.Connecting("")))
        assertTrue(w.onState(same))
    }

    @Test fun `one attempt emitted twice counts once`() {
        // 🔴 Measured on the device (2026-09-16): one failure arrives as Retrying(Error…) plus
        //    Retrying(Disconnected). Without folding them, "3 attempts" is really 1.5 and the logs
        //    lie.
        val w = StartupFailureWatcher(attemptsBeforeOpen = 3)
        repeat(2) {                                   // two attempts
            assertFalse(w.onState(VncConnectionState.Connecting("")))
            assertFalse(w.onState(VncConnectionState.Retrying("Error: refused")))
            assertFalse(w.onState(VncConnectionState.Retrying("Disconnected")))
        }
        assertFalse(w.onState(VncConnectionState.Connecting("")))
        assertTrue(w.onState(VncConnectionState.Retrying("Error: refused")))   // opens on the third
    }

    @Test fun `a failure retrying cannot help opens immediately`() {
        val w = StartupFailureWatcher(attemptsBeforeOpen = 3)
        assertTrue(w.onState(VncConnectionState.Failed("Authentication failure")))
    }

    @Test fun `it opens at most once per session and never reopens over a dismissal`() {
        val w = StartupFailureWatcher(attemptsBeforeOpen = 1)
        assertTrue(w.onState(retry()))
        repeat(10) { w.onState(VncConnectionState.Connecting("")); assertFalse(w.onState(retry())) }
        assertFalse(w.onState(VncConnectionState.Failed("nope")))
    }

    @Test fun `once connected it never opens again, so a mid-game drop is not covered`() {
        val w = StartupFailureWatcher(attemptsBeforeOpen = 2)
        assertFalse(w.onState(retry()))                       // one futile attempt first
        assertFalse(w.onState(VncConnectionState.Connected))  // now connected
        repeat(20) { w.onState(VncConnectionState.Connecting("")); assertFalse(w.onState(retry())) }  // however often it drops after that
        assertFalse(w.onState(VncConnectionState.Failed("the server died")))
    }

    @Test fun `connecting also forgets the running count`() {
        // 🔑 Otherwise two failures before and one after would add to three and it would open at
        //    entirely the wrong moment.
        val w = StartupFailureWatcher(attemptsBeforeOpen = 3)
        w.onState(retry()); w.onState(VncConnectionState.Connecting("")); w.onState(retry())
        w.onState(VncConnectionState.Connected)
        assertFalse(w.onState(retry()))
    }
}
