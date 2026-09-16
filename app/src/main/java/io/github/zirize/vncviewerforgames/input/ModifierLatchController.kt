// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.input

/** State of an on-screen modifier button. */
enum class LatchState {
    /** Not armed. */
    OFF,
    /**
     * **A finger is holding the button down** — treated exactly like a physical keyboard:
     * left hand on Ctrl, right hand clicking or typing.
     * 🔑 While this lasts, ONESHOT is **not** consumed — you may need to press several times.
     */
    HELD,
    /** Applies to exactly one following key. */
    ONESHOT,
    /** Stays armed until pressed again. */
    LOCK,
}

/** How key input should *feel*. 🔑 Mirrors [PointerConfig], so the settings screen draws both the same way. */
data class KeyConfig(
    /**
     * Whether tapping arms the modifier instead of just pressing it.
     *
     * 🔑 **Default `false` (hold-only) on purpose.** Here `CTRL` is not "the Ctrl of Ctrl+C", it is
     * **a game key**. For a game key, held-or-not *is* the meaning, and arming it is the confusing
     * behaviour. Turn this on and tap = ONESHOT, double-tap = LOCK come back, which is what you
     * want when using it as a combining key in a document.
     * Even with it off, **hold-and-use still works** — it is simply simpler.
     */
    var latchEnabled: Boolean = false,
    /** A second tap inside this window means LOCK. ❓ 300ms is a feel judgement, not a measured one. */
    var doubleTapMs: Long = 300,
    /**
     * Whether a mouse click consumes ONESHOT.
     * ❓ Starts at false: use LOCK if you want Ctrl+click. This one depends on what a given game
     * expects, so it is a guess until someone says otherwise.
     */
    var mouseClickConsumesOneshot: Boolean = false,
)

/**
 * Modifier latching — the layer that lets one finger type `CTRL+C`.
 *
 * 🔴 **A separate layer above [KeyInputController].** The ledger must not know about policy; that
 * is what keeps it stable when the latching rules change. Policy is decided here, and every
 * command that actually goes out is still built through the ledger — which is what keeps the
 * "everything can be released" property true.
 *
 * 🔴 Main thread only.
 */
class ModifierLatchController(
    private val keys: KeyInputController,
    var config: KeyConfig = KeyConfig(),
) {

    private val latched = LinkedHashMap<Int, LatchState>()
    private val lastTapMs = HashMap<Int, Long>()
    /** Modifiers a finger is currently on. */
    private val fingerDown = HashSet<Int>()
    /** Modifiers where something (a key, a click) actually happened while they were held. */
    private val actedWhileHeld = HashSet<Int>()
    /** State immediately before the press — decides what the release does. */
    private val prevState = HashMap<Int, LatchState>()

    /** Called whenever latch state changes; this is what the on-screen buttons colour themselves from. */
    var onLatchChanged: ((Map<Int, LatchState>) -> Unit)? = null

    fun stateOf(keySym: Int): LatchState = latched[keySym] ?: LatchState.OFF

    /** The currently armed latches. 🔑 A copy, so a caller holding it will not see it mutate. */
    val states: Map<Int, LatchState> get() = LinkedHashMap(latched)

    /**
     * A finger **landed** on an on-screen modifier button.
     * 🔑 This only presses. Whether it stays armed is decided **on release** ([releaseModifier]).
     */
    fun pressModifier(keySym: Int, nowMs: Long): List<KeyCommand> {
        if (keySym !in MODIFIERS) return keys.press(keySym)
        if (!config.latchEnabled) return keys.press(keySym)

        prevState[keySym] = stateOf(keySym)
        fingerDown.add(keySym)
        actedWhileHeld.remove(keySym)
        val cmds = if (keySym in keys.pressed) emptyList() else keys.press(keySym)
        setState(keySym, LatchState.HELD)
        return cmds
    }

    /**
     * The finger **lifted**. This is where "does it stay armed" gets decided.
     *
     * 🔑 **The test is whether anything happened *while* it was held.**
     * - Something did ⇒ it was used **like a physical key** (left hand on Ctrl, right hand
     *   clicking) ⇒ **release it**.
     * - Nothing did ⇒ it was **a tap meant to arm it** ⇒ `ONESHOT`, or `LOCK` on a fast second tap.
     *
     * ⇒ The same button behaves sensibly whether you use one hand or two.
     * ❓ A *slow* second tap out of ONESHOT is treated as cancel (OFF). Nothing specified that;
     *    it is an assumption.
     */
    fun releaseModifier(keySym: Int, nowMs: Long): List<KeyCommand> {
        if (keySym !in MODIFIERS) return keys.release(keySym)
        if (!config.latchEnabled) return keys.release(keySym)

        fingerDown.remove(keySym)
        val prev = prevState.remove(keySym) ?: LatchState.OFF
        val acted = actedWhileHeld.remove(keySym)
        val fast = nowMs - (lastTapMs[keySym] ?: Long.MIN_VALUE) <= config.doubleTapMs
        lastTapMs[keySym] = nowMs

        return when {
            // something happened while held = used like a physical key
            acted -> { setState(keySym, LatchState.OFF); keys.release(keySym) }
            // pressing a locked one releases it
            prev == LatchState.LOCK -> { setState(keySym, LatchState.OFF); keys.release(keySym) }
            // fast second tap = lock
            prev == LatchState.ONESHOT && fast -> { setState(keySym, LatchState.LOCK); emptyList() }
            // slow second tap = cancel
            prev == LatchState.ONESHOT -> { setState(keySym, LatchState.OFF); keys.release(keySym) }
            // newly armed
            else -> { setState(keySym, LatchState.ONESHOT); emptyList() }
        }
    }

    /** Press and release (a tap). For tests and callers that have no notion of hold time. */
    fun tapModifier(keySym: Int, nowMs: Long): List<KeyCommand> =
        pressModifier(keySym, nowMs) + releaseModifier(keySym, nowMs)

    /** An ordinary on-screen key was pressed. Consumes ONESHOT. */
    fun tapKey(keySym: Int): List<KeyCommand> {
        markActed()
        return keys.tap(keySym) + consumeOneshots()
    }

    /**
     * A key from a physical keyboard.
     * 🔑 **Physical wins.** If the same keysym is pressed physically, the latch folds — the
     * ledger's "pressed" then belongs to the physical key. An ordinary physical key consumes
     * ONESHOT like any other.
     */
    fun onPhysicalKey(keySym: Int, down: Boolean): List<KeyCommand> {
        if (keySym in MODIFIERS) {
            if (stateOf(keySym) != LatchState.OFF) setState(keySym, LatchState.OFF)
            return if (down) keys.press(keySym) else keys.release(keySym)
        }
        markActed()
        val cmds = if (down) keys.press(keySym) else keys.release(keySym)
        // 🔑 Consume after the key is released, not before - same ordering as everywhere else.
        return if (down) cmds else cmds + consumeOneshots()
    }

    /** A mouse click happened. By default this does **not** consume ONESHOT. */
    fun onMouseClick(): List<KeyCommand> {
        markActed()
        return if (config.mouseClickConsumesOneshot) consumeOneshots() else emptyList()
    }

    /** Release every held key and clear every latch. */
    fun releaseAll(): List<KeyCommand> {
        val cmds = keys.releaseAll()
        fingerDown.clear(); actedWhileHeld.clear(); prevState.clear()
        if (latched.isNotEmpty()) {
            latched.clear()
            lastTapMs.clear()
            notifyChanged()
        }
        return cmds
    }

    /** The connection changed - clear the ledger and the latches *quietly*; there is nowhere to send to. */
    fun reset() {
        keys.reset()
        fingerDown.clear(); actedWhileHeld.clear(); prevState.clear()
        if (latched.isNotEmpty()) {
            latched.clear()
            lastTapMs.clear()
            notifyChanged()
        }
    }

    /** 🔑 Mark every modifier currently under a finger as "used", so the release clears it. */
    private fun markActed() {
        actedWhileHeld.addAll(fingerDown)
    }

    /** 🔴 [LatchState.HELD] is **not** consumed - while held you may need to press several times. */
    private fun consumeOneshots(): List<KeyCommand> {
        val spent = latched.filterValues { it == LatchState.ONESHOT }.keys.toList()
        if (spent.isEmpty()) return emptyList()
        spent.forEach { latched.remove(it) }
        notifyChanged()
        return spent.flatMap { keys.release(it) }
    }

    private fun setState(keySym: Int, state: LatchState) {
        if (state == LatchState.OFF) latched.remove(keySym) else latched[keySym] = state
        notifyChanged()
    }

    private fun notifyChanged() {
        onLatchChanged?.invoke(LinkedHashMap(latched))
    }

    private companion object {
        val MODIFIERS = setOf(
            VncKeySym.ControlL, VncKeySym.ControlR,
            VncKeySym.ShiftL, VncKeySym.ShiftR,
            VncKeySym.AltL, VncKeySym.AltR,
            VncKeySym.SuperL,
        )
    }
}
