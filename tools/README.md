# Measurement and diagnosis tools

All of these run on the host, with a device showing as `device` in `adb devices`.

| tool | what it does |
|---|---|
| `verify_frames.sh` | 🔑 **The regression test.** Answers PASS/FAIL on "is the screen actually updating", using two independent pieces of evidence: the app's `PERF` counters and a binary screenshot comparison. Both must hold. |
| `stress.sh` | Injects touches to keep the server screen changing, and measures sustained frame rate. Doubles as a check that the input path works. |
| `ctrl_client.py` | 🔑 **A control.** Connects to the VNC server directly from this host and sees whether non-incremental requests get answered — which separates "the server is at fault" from "the app is". |
| `bench/modifier_persist_test.py` | Do modifiers stay pressed on the server after a client dies? Xtigervnc: no. ❓ x11vnc: never measured. |
| `bench/make_stress_clip.sh` | 🔑 **Builds load clips JPEG cannot cope with**, in five stages (flash, plasma, mosaic, stripes, noise) so you can tell *where* it breaks. Feed one to `fullmotion_server.sh` with `VNCBENCH_CLIP=<path>`. |
| `bench/fullmotion_server.sh` | 🔑 **The full-motion bench.** Stands up a VNC server playing the *same* clip every time, so load is reproducible. `start` generates a one-time password and writes it into `vnc.dev.*` in `local.properties`, so a rebuilt app connects straight to it. **Always `stop` afterwards** (deletes the password, restores the other server). |
| `bench/idle_wake_probe.sh` | Stutter, on five axes at once. See below. |
| `bench/input_lag_probe.sh` | The lag a **finger** feels, measured separately from the screen. See below. |
| `compare_preview_to_device.py` | Checks that an SVG layout preview and the real device screen agree. |
| `proxy2.py` | A TCP proxy that captures the actual wire between client and server. Point the app at `127.0.0.1` and pair it with `adb reverse tcp:5900 tcp:5900`. |

🔴 **Why a screenshot comparison alone is not enough**: if the server screen itself did not change
it reports "identical", and if a launcher screen creeps in it reports "different". That is why
`verify_frames.sh` reads the log evidence alongside it.
🔑 The fastest thing for a human to check is the clock in the corner of the remote screen.

⚠️ `proxy2.py` cannot be used over a direct LAN connection when the firewall blocks 5900. Use
`adb reverse` instead — it touches no firewall rules. Afterwards, always run
`adb reverse --remove-all` and put the target address back.

## 🔴 If you change what the harness greps for, change the harness

In 2026-09-15 the `framebufferUpdateStart/End` debug logs were removed from `VncEngine`, and
`verify_frames.sh` promptly reported **FAIL on a perfectly healthy app** (the screen comparison said
DIFF). It now keys off the `PERF` summary lines instead. Touch the hot-path logging again and this
file has to be looked at too.

---

## Measuring full-motion performance A/B

```bash
tools/bench/fullmotion_server.sh start      # a 30fps full-motion server
./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop tech.doldam.remotepad
adb shell monkey -p tech.doldam.remotepad -c android.intent.category.LAUNCHER 1
timeout 35 adb logcat -s PERF:I             # about 30 seconds of summaries
tools/bench/fullmotion_server.sh stop       # 🔴 always
```

🔑 **What to look at** — not fps, but the **ratio of `worker` to `decode`**. `worker` is the sum of
time the four decoder threads actually spent working; `decode` is the message loop's wall clock. If
the sum **exceeds** the wall clock, work is genuinely running in parallel. If it cannot, something
is serialising. `maxPar` (the most workers running at once during that second) counts the same fact
directly: **4 is healthy, 1 is broken.** `starve` counts times a worker found nothing it could take
although the queue was not empty — if it is large, suspect the dispatcher.

---

## `bench/make_stress_clip.sh` — load that compression cannot absorb

🔑 **Why testsrc2 is not enough**: the clip `fullmotion_server.sh` generates by default moves only
part of the screen and has large flat areas, so **JPEG handles it well** (83KB per frame). Labelled
"full-screen 30fps", it still only produces about 20Mbps on the wire.

```bash
bash bench/make_stress_clip.sh                                  # five stages, 20s (~120MB, in /tmp)
bash bench/make_stress_clip.sh --stage noise --secs 10 --noise-block 1   # just the worst case, longer
bash bench/make_stress_clip.sh --fps 60 --secs 3                # a 60fps version
VNCBENCH_CLIP=/tmp/vncbench_stress.mp4 bash bench/fullmotion_server.sh start
```

**Each stage attacks something different** — measured per frame at JPEG q≈80, 1920x1080:

| stage | picture | per frame | @30fps | what it targets |
|---|---|---:|---:|---|
| `flash` | a flat colour flipping every frame | 33KB | 8Mbps | almost no bytes, whole screen dirty → dirty-region detection and round-trip cost alone |
| `plasma` | saturated colour flowing quickly | 123KB | 30Mbps | where chroma subsampling stops helping |
| `mosaic` | 12px colour blocks, refreshed every frame | 443KB | 109Mbps | closest to real content (confetti, particles) |
| `stripes` | a 6px primary grid inverting every frame | 1016KB | 250Mbps | worst case for JPEG's 8x8 DCT; the ringing is visible |
| `noise` | 2px uniform RGB random | 1278KB | 314Mbps | **incompressible.** The hard ceiling of bandwidth and decoder |
| (control) `testsrc2` | the default clip | 83KB | 20Mbps | — |

🔴 **`stripes` and `noise` will not fit down gigabit Ethernet**, so when fps drops you must first
separate "the decoder is slow" from "the wire is narrow" (the `worker`/`decode` ratio in `PERF:I`).
⚠️ Over Wi-Fi the wire is already the limit at `mosaic`. Try a lighter stage before suspecting the
decoder.

⚠️ **Do not raise `--crf` to save space.** Softening the source removes high-frequency detail and
**weakens the test itself**. Reduce `--secs` instead; the intensity is unchanged.
🚫 The clips are never committed — that is why the default output is `/tmp`.

---

## `bench/idle_wake_probe.sh` — stutter, on five axes

```bash
VNCDISP=:5 bash bench/idle_wake_probe.sh 45 --restart          # 45s from an app restart
VNCDISP=:5 bash bench/idle_wake_probe.sh 45 --restart --drive  # inject the input as well
bash bench/idle_wake_probe.sh 45 --busy                        # a control with the cores kept awake
```

**No single axis settles any hypothesis**, so five are overlaid on the same second:

| axis | what it separates |
|---|---|
| 1. CPU frequency | if it is power management, this drops while `decode` and `worstDec` rise for the same work |
| 2. `decode ms/s` | how much the CPU actually did |
| 3. `MB/s`, `worstGap` | is the network blocked |
| 4. `input=` | did the client even send the input |
| 5. **remote cursor movement** | did that input change anything on the server |

🔑 **Axis 5 is the point of this tool.** Without it, "no screen updates are arriving" and "the
server has nothing to send" wear **exactly the same face**. That is how it was misread once: the
injected swipes had pushed the cursor into the corner of the screen (2339,1079) and pinned it
there, and the result was reported as "input goes out but no screen updates come back".

### 🔴 It refuses to judge a measurement it cannot trust

1. **The cursor did not move** → "do not read this as no screen updates".
2. **`worstGap > 2000ms`** → "the device screen went off and the app stopped drawing; do not use
   this for a load comparison." (Those numbers were once read as "a light load" and a conclusion
   was published and then withdrawn. The probe now holds the screen awake with
   `svc power stayon true` and restores the original setting via `trap`.)
3. **Injected input, but the frequency did not move** → "the hardware input booster may not have
   woken. Measure again with a real hand."

⚠️ **`VNCDISP` must point at the X display of the server the app is *currently* connected to.**
Pointed anywhere else, axis 5 produces a fake zero. If that display is unreachable, the right move
is to turn the axis **off**.

### Reproducible load

🚫 Load produced by hand cannot be measured twice. `mpv --loop-file=inf --fs --vo=x11` plays **the
same file** every time.
🔑 `--vo=x11` is required: with a GPU overlay the pixels never reach the X framebuffer, so **the VNC
server sees nothing at all**. Run-to-run variance was measured: **fps ±1%, rect/s ±3%, worstDec 0%**
⇒ **only treat differences above 10% as real.**

---

## `bench/input_lag_probe.sh` — the lag a finger feels, measured separately

🔴 **Screen axes cannot judge input.** `fps` and `worstDec` get worse under load by definition, so
they cannot say whether input got worse with them.
🔴 And `input=` counts **events only** — every one of them could be 200ms late and that axis would
still look healthy.

```bash
bash bench/input_lag_probe.sh <label>                 # quiet 10s / load 25s / stopped 10s
INPUTLAG_CLIP=/tmp/stress_noise.mp4 bash bench/input_lag_probe.sh noise
INPUTLAG_LOAD=180 bash bench/input_lag_probe.sh sustained     # long enough to get hot
```

### Reading it — three axes, kept apart

| axis | measures | large means |
|---|---|---|
| `inLat` | event **created** → `onTouchEvent` handles it | **the UI thread is behind** |
| `inSnd` | "send this" → the send thread **starts** | a queue formed (the previous write had not finished) |
| `inW` | the write itself | `synchronized(os)` or the socket |

🔑 **worst matters more than avg.** Stutter does not show up in an average — one 100ms delay per
second leaves the mean looking fine.
🔑 Judge on the **load ÷ quiet ratio**. Absolute values depend on the device and on how input was
injected.

### ⚠️ Know the limits before using it

- **Injected input is not a human hand** and may not wake the vendor's input booster. The numbers
  are **for A/B comparison**; "it is fixed" can only be closed by a real hand.
- **Injection runs at about 58 events/s.** A finger is 120–240Hz, so this **cannot see the regime
  where a queue builds up**.
- 🔴 **Tail values under saturation vary a lot run to run** — under identical conditions, `inSnd`
  worst came out as 20, 29, 37 and 28ms. **The 10% rule does not apply on that axis.** On a
  deliberately saturated device, read the trend and do not declare a verdict.

---

## 🔴 Rule one: no control, no verdict

Before using "nothing found" as a conclusion, **check that the same instrument can find something
when something is there.**

🔑 **Why this is written down as a rule**: when an instrument breaks, it usually reports *nothing*.
And the answers we are looking for are usually "nothing" too — nothing persisted, nothing leaked,
nothing broke. **A broken instrument and the right answer wear the same face.** So "nothing" is not
evidence by itself.

**Two real cases**, both while building `modifier_persist_test.py`:

1. `xinput --query-state` was aimed at the **master keyboard**, which does not support that query
   at all (presses only show on slaves `5` and `7`).
2. Assembling `env` by hand in Python dropped **`XAUTHORITY`**, so `xinput` failed silently with
   empty stdout.

🔴 **Both looked like "no keys pressed", which was exactly the hoped-for answer.** Without the
control — was the key genuinely down *before* disconnecting — a wrong conclusion would have been
reported with confidence.

**A third case: screen *rotation* cannot be seen with `screencap`.** Comparing corner brightness
before and after a 180° flip gave **byte-identical** results, which read as "the rotation did not
happen" and as a broken feature. It had rotated — confirmed by eye. `screencap` captures **in the
display orientation**, so a 180°-rotated screen comes out upright; it cannot see this in principle.
⇒ Some axes are invisible to the instrument by construction, and then a human eye is the only
control.
🚫 **Never read "the screenshots match" as "nothing changed".**

⇒ Every tool here is built to have this property:

- `verify_frames.sh` reads **two** kinds of evidence (PERF log and screenshot).
- `modifier_persist_test.py` **refuses to report** and exits when the control comes back empty.
- When writing a new tool, **first write down what it outputs when it is broken.**

---

## Proving a layout change without a device

🔑 Layouts live in **`profiles/`**, not in code. After changing one there are three checks.

```bash
./gradlew :app:previewProfiles      # this one line does 1 and 2 together
```

| | check | what it prevents |
|---|---|---|
| 1 | **the validator** (`OverlayProfileValidatorTest`) | losing the settings exit, **an uppercase keysym**, spilling out of the panel, overlap, typos |
| 2 | **the SVG preview** (`app/build/preview/<id>.svg`) | a layout where the numbers are legal but the result looks wrong |
| 3 | **comparison against the device** (below) | the preview and the real screen having drifted apart |

```bash
# 3 needs a device. No SVG rasteriser required.
adb exec-out screencap -p > /tmp/shot.png
python3 tools/compare_preview_to_device.py app/build/preview/default.svg /tmp/shot.png
```

🔴 **Losing the settings exit is the one that matters most.** "Get rid of that settings button" is
something a person can ask without thinking, and following it leaves the user with **no way to
change anything from inside the app.** It happened for real and had to be reverted, so it is now a
rule.

🔑 **An uppercase keysym cannot be caught by looking.** A button labelled `F` whose keysym is also
`F` sends *F with Shift held* in X11. **It builds, the screen is fine, and only the game fails to
respond.**

**Building a different layout**

```bash
./gradlew :app:assembleRelease -PvncProfile=lefty.json   # uses profiles/lefty.json
```

**Deliberately broken examples** — the five files in `app/src/test/resources/broken-profiles/` are
the validator's control group. 🔑 When changing the validator, check **those five still fail** first.
