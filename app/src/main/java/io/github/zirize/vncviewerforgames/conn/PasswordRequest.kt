// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One "the server wants a password" question, asked from the network thread and answered on the UI.
 *
 * 🔑 Why it exists: TigerVNC asks for the password **synchronously**, from inside the security
 *    handshake on the connection thread (`CSecurityVncAuth` → `UserPasswdGetter`). The answer has to
 *    come from a dialog on the UI thread. So the network thread parks here until the dialog answers.
 *
 * 🔴 Until 2026-11-24 nothing answered at all: the hook existed but was never wired, and the only
 *    password the app could ever send was `vnc.dev.password` from `local.properties` — which is
 *    blank in every published build. A Play install could therefore reach **only servers with no
 *    password**, and failed on every other one with an empty-password auth failure.
 *
 * Answering is first-wins and idempotent: [answer] and [cancel] after the first call do nothing,
 * so the dialog, a destroyed surface and a timeout can all race without harm.
 * A `null` answer means "no password"; the caller sends nothing and the server's refusal ends the
 * attempt as an auth failure (fatal, so it is not retried into a lockout).
 */
class PasswordRequest(
    /** `host:port`, shown in the dialog so the user knows which server is asking. */
    val target: String,
) {
    private val done = CountDownLatch(1)
    @Volatile private var result: String? = null
    @Volatile var isDone: Boolean = false
        private set

    fun answer(password: String?) = finish(password?.takeIf { it.isNotEmpty() })

    fun cancel() = finish(null)

    @Synchronized
    private fun finish(value: String?) {
        if (isDone) return
        result = value
        isDone = true
        done.countDown()
    }

    /**
     * Blocks the calling (network) thread until answered.
     * 🔑 The timeout is a backstop for a dialog that never appears (the UI went away between the
     *    post and the draw); it is long enough that no person typing is ever cut off.
     */
    fun await(timeoutMs: Long = TIMEOUT_MS): String? {
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel()
        return result
    }

    companion object {
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }
}

/**
 * The password typed for one server, kept **in memory only** for the life of the view, so that the
 * automatic reconnect after a dropped connection does not put the dialog up again mid-game.
 *
 * 🔴 Never written to preferences (`SettingsCodec` deliberately does not store passwords).
 * 🔑 Bound to `host:port`: pointing the app at another server must not send it this password.
 * 🔑 Only promoted to "remembered" once the connection is actually up ([confirm]); an auth failure
 *    ([forget]) drops both, so the next attempt asks again instead of repeating a wrong answer.
 */
class SessionPassword {
    @Volatile private var remembered: Pair<String, String>? = null
    @Volatile private var pending: Pair<String, String>? = null

    fun lookup(target: String): String? = remembered?.takeIf { it.first == target }?.second

    /** A password was just typed for [target]; not trusted until [confirm]. */
    fun offer(target: String, password: String) { pending = target to password }

    /** The connection that used the [offer]ed password came up. */
    fun confirm() { pending?.let { remembered = it }; pending = null }

    /** The server rejected the password. */
    fun forget() { remembered = null; pending = null }
}
