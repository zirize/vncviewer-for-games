// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames

import android.graphics.Bitmap

interface VncEngineCallback {
    fun onConnectionStateChanged(state: String)
    fun onDesktopSizeChanged(width: Int, height: Int)
    fun onFrameUpdated()
    /**
     * Engine requests a Bitmap of the specified size to render into.
     * The UI should provide a Bitmap (preferably ARGB_8888).
     */
    fun onRequestBitmap(width: Int, height: Int): Bitmap?
    fun onClipboardDataReceived(data: String)

    /**
     * The server sent a **cursor shape** (only arrives when `cursorShape` is on).
     * 🔑 Once this arrives the server stops compositing the cursor into the framebuffer — drawing
     *    it becomes our job. ⇒ The cursor **no longer waits for a frame**; it follows the finger
     *    where the finger is.
     * [argb] is four bytes per pixel in A,R,G,B order (see `CMsgReader.readSetCursor`).
     * 🔑 A [width] or [height] of 0 means "hide the cursor".
     */
    fun onCursorShapeChanged(width: Int, height: Int, hotspotX: Int, hotspotY: Int, argb: ByteArray)

    /**
     * The connection is **fully** up (RFBSTATE_NORMAL). From here on input actually goes out.
     * 🔑 This is where the input ledger is reset and modifiers are normalised — anything earlier
     *    is dropped silently by the send gate (`canSendInput`).
     */
    fun onConnectionReady()

    /**
     * A failure that retrying cannot help. Do not schedule a reconnect.
     * For example an auth failure: the same password gives the same answer, and all it does is
     * fill the server's log or trip a lockout policy.
     */
    fun onFatalFailure(message: String)
}
