# Measuring without fooling yourself

## Rule one: a broken instrument looks exactly like a broken program

If two sessions drive the same device at once, one side's `am force-stop` or `install` kills what
the other is measuring, and `adb logcat -c` wipes the evidence. The result reads as "performance is
bad" or "input is not arriving" — **identical to a real defect**.

So: one session owns the device. If you need a measurement and someone else holds the device, ask
them for the number; do not reach for `adb` yourself.

## Build release, not debug

`debuggable` makes ART give up optimisations. Measured on a full-motion test: **20.8 fps debug vs
33.2 fps release**, with worse stutter too. A debug build will send you chasing a problem you
created.

## Reproducible load

```bash
tools/bench/fullmotion_server.sh start   # local VNC server, fixed clip, one-time password
# ... measure ...
tools/bench/fullmotion_server.sh stop    # 🔴 always
```

Numbers taken against someone's desktop with a video playing cannot be reproduced later, and a
comparison against a number you cannot reproduce is not a comparison.

`stop` deletes the one-time password and restores the other test server — note that it also
*starts* that server, so if nothing was running before, kill it afterwards.

🔑 The dev target is wired through `local.properties` (untracked), which is **baked into
BuildConfig**. Start the bench server and the app will still use the old target until you
**rebuild**.

## Read the right number

`adb logcat -s PERF:I` prints a one-second summary. For anything about parallelism, compare
`worker` (sum over threads) with `decode` (wall clock) — if the sum exceeds the wall clock, it is
genuinely parallel. `maxPar` counts it directly. **fps alone will mislead you**; it moves with
content as much as with code.

## Measure before you believe a document

Several claims in the notes here were true when written and false a week later — most notably
"decoding is the bottleneck". If a document tells you where the problem is, check that it still is
before you spend a day there. That includes this file.
