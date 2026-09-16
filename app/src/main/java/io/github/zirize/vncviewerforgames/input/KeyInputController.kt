// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

/** One RFB KeyEvent. */
data class KeyCommand(val keySym: Int, val down: Boolean)

/**
 * The ledger of **which keys are currently down**. Every key that goes out passes through here.
 *
 * 🔴 **Why it exists** — the pointer side has [PointerInputController.releaseAll] to let go of
 * held buttons when the screen goes away or the connection drops. The key side had nothing.
 * `onKeyDown`/`onKeyUp` pair up by themselves so it never bit, but the on-screen `CTRL` and `ALT`
 * (held), and the D-pad, break that pairing: slide a finger off a button and the key stays down on
 * the server **forever**, with nothing on screen to say why.
 *
 * 🔑 **This ledger knows nothing about policy.** Modifier latching (one-shot, lock) is a separate
 * layer on top. Put latching in here and the ledger moves every time the latching rules change.
 *
 * 🔴 Main thread only — no synchronisation (same contract as [PointerInputController]).
 */
class KeyInputController {

    /** 🔑 Insertion-ordered, so releases can replay the order things were pressed in. */
    private val down = LinkedHashSet<Int>()

    /**
     * Called only when the pressed set actually changes; this is what the on-screen CTRL button
     * colours itself from.
     * 🔴 If the UI kept its own copy we would have two versions of the truth, the same trap the
     * pointer side already avoids.
     */
    var onPressedChanged: ((Set<Int>) -> Unit)? = null

    /** The keysyms currently down. 🔑 A copy, so a caller holding it will not see it mutate. */
    val pressed: Set<Int> get() = LinkedHashSet(down)

    /**
     * Press.
     * 🔴 **A down is emitted even if the key is already down.** The ledger is idempotent about
     * *state*, not about traffic. Swallow the send as well and auto-repeat breaks: holding a
     * physical key makes Android raise `onKeyDown` repeatedly, and passing those through *is* how
     * repeat is implemented here. (Measured 2026-09-15: one tap of A = 2 events, a long press = 3;
     * the third is the repeat down.)
     */
    fun press(keySym: Int): List<KeyCommand> {
        if (down.add(keySym)) notifyChanged()
        return listOf(KeyCommand(keySym, true))
    }

    /** Release. 🔑 An up for a key that is not down is **not** sent - if the ledger does not have it, the server does not either. */
    fun release(keySym: Int): List<KeyCommand> {
        if (!down.remove(keySym)) return emptyList()
        notifyChanged()
        return listOf(KeyCommand(keySym, false))
    }

    /** Press and release. */
    fun tap(keySym: Int): List<KeyCommand> = press(keySym) + release(keySym)

    /**
     * Release **every** held key.
     * 🔴 **Non-modifiers first, modifiers last.** The other order leaves a window where Ctrl has
     * already gone but the other keys are still down on the server as bare keys — in a game that
     * one tick fires as a misinput.
     */
    fun releaseAll(): List<KeyCommand> {
        if (down.isEmpty()) return emptyList()
        val (mods, others) = down.partition { it in MODIFIERS }
        down.clear()
        notifyChanged()
        return (others + mods).map { KeyCommand(it, false) }
    }

    /**
     * Clears the ledger only. 🔑 **Sends nothing.** Used where the connection changes: the old
     * server cannot be reached anyway, and the new one has nothing to release.
     */
    fun reset(): List<KeyCommand> {
        if (down.isEmpty()) return emptyList()
        down.clear()
        notifyChanged()
        return emptyList()
    }

    /**
     * Sends one up per modifier right after connecting. Does not touch the ledger.
     *
     * 🔑 **Why insure against this** — after an abnormal exit (force-stop, network drop) the
     * releases *cannot* be sent; the socket is already gone. So if a modifier is stuck on the
     * server, there is no way to fix it from inside the app.
     * Xtigervnc was measured to release them itself when a client disconnects
     * (`tools/bench/modifier_persist_test.py`). ❓ x11vnc, which is the actual target here, was
     * **never measured**.
     * ⇒ The cost (seven ups, once per connection) and the failure (unfixable from inside the app)
     * are so lopsided that the insurance is worth it. Drop it once x11vnc can be measured.
     * 🔑 An up for a key that is not down is harmless to the server.
     */
    fun normalizeModifiers(): List<KeyCommand> = MODIFIERS.map { KeyCommand(it, false) }

    private fun notifyChanged() {
        onPressedChanged?.invoke(LinkedHashSet(down))
    }

    private companion object {
        /** 🔑 This order is also the order [normalizeModifiers] emits in. */
        val MODIFIERS = listOf(
            VncKeySym.ControlL, VncKeySym.ControlR,
            VncKeySym.ShiftL, VncKeySym.ShiftR,
            VncKeySym.AltL, VncKeySym.AltR,
            VncKeySym.SuperL,
        )
    }
}
