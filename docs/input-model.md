# The input model

How a touch, a key or an on-screen button becomes something the server receives.

This describes **what the code does**, not what was once planned. Where a comment in the code and
this page disagree, the code wins and this page is wrong.

---

## The shape of it

```
on-screen buttons ─┐
physical keyboard ─┼─→ ModifierLatchController ─→ KeyInputController ─┐
                   │        (policy)                  (the ledger)    │
touches ───────────┴─→ PointerInputController ─────────────────────── ┼─→ VncEngine → server
                              (gestures)                              │   (the send gate)
```

Three properties hold everything together:

**Everything passes through one gate.** `VncEngine` is the only place anything reaches the socket,
which is why `viewOnly` can be enforced in exactly one line and new input paths cannot leak past it.

**The ledger knows what is down; the policy layer does not touch the wire.** `KeyInputController`
records every key that is currently pressed. `ModifierLatchController` decides *policy* — one-shot,
lock, held — and builds every command it issues through the ledger. That split is what keeps
"everything can be released" true no matter how the latching rules change.

**One version of the truth.** The cursor position and the held button mask live in
`PointerInputController` and nowhere else. If the UI called the engine directly it would have its
own copy, and the very next touch would snap the cursor back.

All of it is plain Kotlin with no Android imports, so it is tested under JUnit with no device.

---

## Keys: the ledger

`KeyInputController` exists because of an asymmetry. The pointer side always had `releaseAll()` for
when the connection drops or the screen goes away. The key side had nothing — and `onKeyDown` /
`onKeyUp` pair up by themselves, so nothing ever went wrong. Then came on-screen `CTRL` and `ALT`
(held) and the D-pad, which break that pairing: **slide a finger off a button and the key stays
down on the server forever**, with nothing on screen to say why.

Two rules are easy to get backwards:

- **It is idempotent in state, not in traffic.** Pressing a key that is already down still emits a
  down. Swallowing it would break auto-repeat, because holding a physical key makes Android raise
  `onKeyDown` repeatedly and passing those through *is* how repeat is implemented. (Measured: one
  tap of A produces 2 events, a long press 3; the third is the repeat.)
- **An up for a key that is not down is never sent.** If the ledger does not have it, the server
  does not either.

**Release order is non-modifiers first, modifiers last.** The other order leaves a window where
Ctrl has already gone while the other keys are still down on the server as bare keys — in a game
that one tick fires as a misinput.

### Insurance at connect time

Right after connecting, an up is sent for each of the seven modifiers. After an abnormal exit — a
force-stop, a dropped network — the releases *cannot* be sent, because the socket is already gone.
So if a modifier is stuck on the server there is no way to fix it from inside the app.

Xtigervnc was measured to release a disconnected client's keys by itself. ❓ x11vnc, which is the
actual target here, was never measured. The cost is seven ups once per connection; the failure it
prevents is unfixable. Drop the insurance once x11vnc can be measured.

---

## Modifiers: latching

A modifier button has four states: `OFF`, `HELD`, `ONESHOT`, `LOCK`.

**The decision happens on release, not on press**, and the test is whether anything happened while
the button was held:

- **Something did** — a key, a click ⇒ it was used **like a physical key** (left hand on Ctrl,
  right hand clicking) ⇒ release it.
- **Nothing did** ⇒ it was **a tap meant to arm it** ⇒ `ONESHOT`, or `LOCK` on a fast second tap.

That one rule is why the same button works naturally one-handed and two-handed.

**Latching is off by default.** Here `CTRL` is not "the Ctrl of Ctrl+C", it is a game key — held or
not *is* the meaning, and arming it is the confusing behaviour. Turning it on brings back
tap = one-shot, double-tap = lock, which is what you want when using it as a combining key.

Other rules:
- **While `HELD`, `ONESHOT` is not consumed** — you may need to press several times.
- **A `ONESHOT` clears after the consuming key's down/up pair**, never before.
- **Physical wins.** If the same keysym arrives from a physical keyboard, the latch folds and the
  ledger's "pressed" belongs to the physical key.
- ❓ A *slow* second tap out of `ONESHOT` is treated as cancel. Nothing specified that; it is an
  assumption.

---

## A label is not a keysym

This has its own section because it is the single easiest mistake to make in this codebase.

A button labelled `F` must send lowercase `f` (0x66). In X11 an uppercase `F` is *F with Shift
held*, and a game's "F key" is the lowercase one. Get it wrong and it compiles, the button draws,
the screen is fine, and **only the game fails to respond**.

`VncKeySym.resolve()` accepts three spellings — a constant name (`"PageUp"`), a single character
(`"f"`), or a number (`"0xFF55"`). `input/VncKeys.kt` is the one registry; there is no second table
to keep in sync. The profile validator rejects any keysym in `A`–`Z`.

---

## Pointer

`TRACKPAD` moves relatively; `ABSOLUTE` jumps to where you touched. Two-finger scroll, long press
for right click, and tap-and-a-half to drag.

- **Fractional deltas accumulate** rather than being thrown away; only whole pixels reach the cursor.
- **Starting a drag cancels the long-press timer.** Without that, a right click fires mid-drag.
- **The wheel is a button.** RFB has no wheel event, so one click is a down/up pair on a bit, and
  `clicks` is capped at 20 so rapid repeats cannot balloon the send queue.
- The controller returns a **deadline** rather than setting timers, and the caller supplies the
  clock — which is what makes the gesture logic deterministic under test.

---

## What the engine does on the way out

**Stale moves are dropped.** Consecutive moves with the same button state collapse into the last
one. Under load a single write can take 21ms while the message loop holds `synchronized(os)`, and
the moves behind it queue up; sending them all just replays old positions in order, so the lag a
finger feels stays exactly as it was. Events that **change** the buttons are never dropped — lose
one and a click disappears or a key stays down. A click carries its own coordinates, so coalescing
the moves in front of it cannot make it land somewhere else.

**The send thread runs at raised priority** (−8, close to the UI thread's −10). With the CPU
saturated, at default priority `inSnd` grew from 2ms to 20ms — the send itself is tens of bytes and
cheap; the delay was waiting for a turn. "Drop frames, never delay input" is written once in the
render path and once more in the thread priorities.

**Nothing reaches the socket before `RFBSTATE_NORMAL`.** The gate drops it silently, which is why
the ledger is reset and the modifiers normalised from `onConnectionReady` and not earlier.
