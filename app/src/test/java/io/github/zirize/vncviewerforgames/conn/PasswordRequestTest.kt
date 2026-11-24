// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The password hand-off between the network thread and the dialog, without a device.
 * 🔑 The race that matters: the dialog answering, the surface going away and the timeout can all
 *    land on the same request, and only the first may count.
 */
class PasswordRequestTest {

    @Test
    fun `the waiting thread gets the answer typed on another thread`() {
        val req = PasswordRequest("h:5900")
        var got: String? = "unset"
        val waiter = thread { got = req.await(5_000) }
        req.answer("secret")
        waiter.join(5_000)
        assertEquals("secret", got)
    }

    @Test
    fun `first answer wins - a late cancel does not erase it`() {
        val req = PasswordRequest("h:5900")
        req.answer("secret")
        req.cancel()
        assertEquals("secret", req.await(10))
    }

    @Test
    fun `cancel and an empty answer both mean no password`() {
        assertNull(PasswordRequest("h").apply { cancel() }.await(10))
        assertNull(PasswordRequest("h").apply { answer("") }.await(10))
    }

    @Test
    fun `nobody answering ends in no password after the timeout, not a hang`() {
        val req = PasswordRequest("h")
        assertNull(req.await(20))
        assertTrue(req.isDone)
    }

    @Test
    fun `a typed password is remembered only after the connection comes up`() {
        val s = SessionPassword()
        s.offer("h:5900", "pw")
        assertNull("not trusted before confirm", s.lookup("h:5900"))
        s.confirm()
        assertEquals("pw", s.lookup("h:5900"))
    }

    @Test
    fun `a remembered password is never sent to a different server`() {
        val s = SessionPassword()
        s.offer("h:5900", "pw"); s.confirm()
        assertNull(s.lookup("h:5901"))
        assertNull(s.lookup("other:5900"))
    }

    @Test
    fun `an auth failure forgets it so the next attempt asks again`() {
        val s = SessionPassword()
        s.offer("h:5900", "pw"); s.confirm()
        s.forget()
        assertNull(s.lookup("h:5900"))
    }
}
