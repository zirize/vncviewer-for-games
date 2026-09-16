# Making a layout for someone

Someone said what they want — *"I'm left-handed"*, *"the buttons are too small"*, *"put Esc where
F5 is"*. This is how you turn that into a build, and how to tell whether you got it right.

Every step says what to do when it fails. That part is the point; the happy path is three commands.

---

## 0. Understand the request in buttons, not coordinates

Write down what will change before you open the file. *"Left-handed"* is not a coordinate — it
usually means **swap the panels**: the D-pad and page keys move right, the letter keys move left.
*"Too small"* usually means `w`/`h` up **and** positions respaced, because growing buttons in place
makes them collide.

If the request is genuinely ambiguous, say what you assumed in your reply. Do not guess silently.

> 🔴 **If the request is to remove the settings button, stop.** See [`../AGENTS.md`](../AGENTS.md)
> §2.1. Explain that it is the only way back into the app's own settings, and offer to move or
> shrink it instead.

---

## 1. Copy the default

```bash
cp profiles/default.json profiles/lefty.json
```

Keep the `_comment` blocks. They hold the reasoning — units, and why `label` and `keysym` are
separate — right where someone editing is about to need it.

Change `"id"` and `"name"` to match the filename.

---

## 2. Edit it

Field reference: [`layout-profile.md`](layout-profile.md).

The two things that bite:

- **`x` is measured from the outer screen edge**, for both panels. Moving a button from the left
  panel to the right one does *not* mean negating `x`.
- **A label is not a keysym.** Label `F`, keysym `f`. See §2.2 of `AGENTS.md`.

---

## 3. Validate

```bash
bash scripts/build.sh preview
```

**If it fails**, the message names the button and says how to fix it. The common ones:

| code | what to do |
|---|---|
| `no-settings-exit` | Put a `{"type":"ui","command":"settings"}` button back. Do not delete the check |
| `uppercase-keysym` | Lowercase the `keysym`. Leave the `label` uppercase — that is correct |
| `out-of-panel` | `x + w` must fit in `authoredForMarginPx` (240). Shrink, or move it inward |
| `overlap` | Two buttons occupy the same pixels. Respace the column; do not just shift one by 1px |
| `duplicate-id` | Two buttons share an `id`. Rename one |
| `unreadable` | A typo in `shape`, `keysym`, or `action.type`. Check spelling against the reference |

Go back to step 2 and run it again. There is no penalty for running it repeatedly.

---

## 4. Look at the result

```
app/build/preview/lefty.svg
```

Open it. The grey band is the remote screen, the dashed lines are the panel edges, and each button
is captioned with its `id`.

The validator only knows whether the numbers are legal. **You** have to decide whether the picture
is what the person asked for — whether the thumb can reach the D-pad, whether the spacing looks
deliberate, whether anything is crowding the edge.

If it is wrong, go back to step 2. If it is right, this SVG is what you show the person.

---

## 5. Build it

```bash
bash scripts/build.sh release -PvncProfile=lefty.json
```

The APK lands in `app/build/outputs/apk/release/`. `bash scripts/build.sh install` builds and
pushes to a connected device in one go.

The build refuses two things on its own, before compiling anything: a profile name that does not
exist, and a profile with no settings exit. Everything else is step 3's job — `assembleRelease`
does not run the tests, so **skipping step 3 is skipping the checks.**

**If the build fails**, it is almost never the profile — the profile was already validated in step
3. Run `bash scripts/doctor.sh`; it checks the JDK, SDK, NDK and the libjpeg-turbo submodule and
changes nothing.

---

## 6. Only if a device is attached

```bash
adb exec-out screencap -p > /tmp/shot.png
python3 tools/compare_preview_to_device.py app/build/preview/lefty.svg /tmp/shot.png
```

This catches the one thing the preview cannot: the preview and the real screen having drifted
apart. It needs no SVG rasteriser.

It only means anything on a 2400×1080 device, because that is the space the preview is drawn in.
On anything else it will tell you the sizes do not match, and that is correct — it is refusing to
compare two different things rather than quietly reporting nonsense.

---

## 7. Tell the person what changed

In their words. *"The D-pad and page keys are on the right now, and Ctrl and Enter are where your
left thumb sits"* — not `panel: left → right`. Attach the SVG.

Say what you assumed, and say what you did **not** do and why — especially if you refused part of
the request.
