# Decoder performance: what is actually the limit

## Disproved hypotheses — do not re-dig these

Each of these was measured, not reasoned about.

| hypothesis | verdict | evidence |
|---|---|---|
| Wi-Fi bandwidth is the limit | ❌ | `compressLevel 0` doubles the bytes but raises throughput 1.8× — the link carries 15.8 MB/s fine |
| The server's encoder is the limit | ❌ | The phone's TCP **rx_queue backs up to 640 KB**. The server is sending; we are not reading |
| libjpeg was built without SIMD | ❌ | 109 NEON symbols present (`jsimd_idct_islow_neon` and friends) |
| JNI `GetPrimitiveArrayCritical` contention | ❌ | Measured inside the JNI call: critical wait = **0 ms/Mpx** |
| `TJPF_BGRA` falls back to C for lack of NEON | ❌ | Switching to `TJPF_BGRX` (which has NEON) gives identical 196–212 ms/Mpx |
| Device power management throttles us | ❌ | During stutter: little core **1785 MHz**, big 2419, prime 2841 — all pinned at maximum. `low_power=0`, on AC, 29.2 °C |
| The idle app gets pinned to little cores | ❌ | `cpuset=/top-app`, `Cpus_allowed_list: 0-7` |
| The network causes the stutter | ❌ | It happens identically on a server with **0.13 ms RTT**, same as over Wi-Fi at 1–48 ms |
| A deeper decode queue would fix it | ⚠️ accomplice, not culprit | 16 → 64 buys fps +8%, starve −18%. Not worth 4× the memory |
| Four parallel decode workers earn their keep | ❌ | One worker (bypassing the dispatcher) is **no worse and sometimes better** — 34.2 fps vs 31.0 on `testsrc2`, and consistently better stutter figures (`worstDec` −14–24%) |

## What is true

`tjDecompress2` costs about **200 ms/Mpx**. But the average rect is 52×52 (2700 px), so a rect
costs roughly **0.5 ms** — meaning the cost is dominated by **per-rect fixed overhead**
(decompressor setup), not by pixel count. At ~1500 rects/s that is 750 ms/s of worker time.

Decoding is **no longer the wall**. After the dispatcher fix below, wall-clock `decode` barely
moved (924 → 881 ms/s) while parallelism went from 1 to 4. What remains serial is the message-loop
thread reading rects off the socket (`readRect`) and waiting for free buffers.

## If you want to make it faster

In rough order of expected value:

1. Reduce the number of rects — that is a server-side Tight splitting policy question.
2. Reuse the decompressor object via the low-level libjpeg API instead of `tjDecompress2`, to
   attack the per-rect fixed cost directly.
3. Turn JPEG off entirely (`-1`) and use the lossless fast path. On the content measured this was
   actually faster: 5.92 vs 5.06 Mpx/s.
4. Rendering is a main-thread software blit (`lockCanvas` + `drawBitmap`), 5–17% of the total.
   `SurfaceHolder.lockHardwareCanvas()` (API 26+) could move it to the GPU. Never tried.

## How to judge a change

🔴 **Not by fps.** Use `worker` (sum across threads) against `decode` (wall clock), from
`adb logcat -s PERF:I`. If the sum across four threads exceeds the wall clock, the work really is
running in parallel — that is a number you cannot fake. `maxPar` counts the same fact directly.

Reproduce load with `tools/bench/fullmotion_server.sh start`, and **`stop` when you are done** —
it deletes the one-time password and restores the other server.
