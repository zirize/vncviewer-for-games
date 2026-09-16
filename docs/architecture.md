# Architecture

A map of what is where, and which parts you can change without a device.

---

## Layout of the repository

| | |
|---|---|
| `profiles/` | **Button layouts.** `default.json` is what ships; `examples/` are starting points |
| `app/src/main/java/io/github/zirize/vncviewerforgames/` | Everything written for this project |
| `app/src/main/java/com/tigervnc/` | The TigerVNC Java client, with local changes marked |
| `app/src/main/cpp/` | The JNI shim; libjpeg-turbo is a submodule underneath it |
| `app/src/test/` | Unit tests, the SVG preview renderer, and the deliberately broken profiles |
| `docs/` | This directory, plus `lessons/` — read that before optimising anything |
| `scripts/` | `doctor.sh`, `build.sh`, `check-private-info.sh` |
| `tools/` | Measurement and diagnosis; see `tools/README.md` |

## Our packages

| package | what lives there |
|---|---|
| `overlay/` | Layout maths, hit testing, drawing, and the profile parser and validator |
| `input/` | The key ledger, modifier latching, pointer gestures, keysym mapping |
| `conn/` | Connection config, connection state, adaptive chroma, the startup-failure watcher |
| `ui/` | Compose screens: the main screen, the connection banner, the settings sheet |
| `settings/` | Keeping the settings the user changed between launches |
| `perf/` | The one-line-per-second performance counters |
| (root) | `VncEngine`, `VncSurfaceView`, `CustomPixelBuffer`, `JpegDecoder` |

---

## The rule that shapes most of it

**Anything that can be decided by arithmetic is kept free of Android**, so it can be tested under
JUnit with no device: `layoutPanel`, `hitTest`, `dpadDirection`, the profile parser and validator,
the key ledger, latching, the pointer gestures, `AutoSubsampling`, `StartupFailureWatcher`.

That is not tidiness. The person changing a layout is usually an agent, and if correctness can only
be established on hardware, an agent can *edit* but never *verify*.

Where a decision needs a clock, **the clock is passed in** rather than read — which is why the
gesture and latching tests are deterministic.

---

## Threads

| thread | does |
|---|---|
| UI | touch, keys, Compose |
| message loop | reads rects off the socket, dispatches to the decoders |
| decode workers (4) | JPEG and Tight decoding, into the shared framebuffer |
| render | `lockCanvas` and blit, **off the UI thread** |
| send (priority −8) | writes input to the socket |

**Drawing was moved off the UI thread** because `renderFrame()` blocks twice over — `lockCanvas()`
waits for vsync, and `synchronized(bmp)` shares a lock with the decoder's commit. While those held
the UI thread, touches queued behind them: on a quiet screen 73% of the UI thread was drawing, and
under load worst input latency went from 27ms to 118ms. Frames are now **dropped** rather than
queued; `framePending` is a flag, not a count, so ten frames arriving mid-draw collapse into one.

**The decode workers write into disjoint rects.** That is enforced by `Region`, which the dispatcher
consults. Both halves of that have been broken before, in ways that depended on each other — see
`docs/lessons/bugs-worth-remembering.md` before touching either.

---

## A frame, end to end

1. The message loop reads a `FramebufferUpdate` and hands each rect to `DecodeManager`.
2. A worker decodes it — JPEG goes through JNI straight into the framebuffer at the rect's own
   position, not the bitmap origin.
3. Pixels land in `CustomPixelBuffer.fb`, an `int[]`. **Not** in the Bitmap: one `setPixels` per
   rect cost 4400 JNI round trips per second, and the cost was per rect rather than per pixel
   (86ms/Mpx to convert against 1108ms/Mpx to upload).
4. At the end of the update, the changed region is uploaded to the Bitmap once. A frame that runs
   long gets committed mid-flight as well, trading a momentary tear for not freezing — ordinary
   frames are untouched, so they do not tear.
5. The render thread blits, and draws the cursor **outside the lock**: even over a frame-old
   picture, the cursor belongs where the finger is now.

## Connecting

`VncConnectionConfig` holds everything negotiated up front. Most of it applies **from the next
connection**, because negotiation happens once at connect time; the settings screen shows the
difference against a snapshot taken then, rather than a sentence saying so. `viewOnly` is the
exception and applies immediately, because the send gate consults it every time.

If the first connection fails, `StartupFailureWatcher` opens the settings sheet by itself. Without
that, a wrong address leaves no way in — the only route to settings is the overlay button, and the
banner only says "retrying".

## Remembering settings

`settings/SettingsCodec` reads and writes what the sheet can change; `SharedPrefsSettingsStore` is
the `SharedPreferences` behind it, and the codec itself touches no `android.*`, so it is tested
under JUnit like everything else here.

It loads in the `VncSurfaceView` constructor — before `surfaceCreated`, i.e. before anything dials
out, so the saved address is the one that is actually used. It saves from the settings sheet's
`changed()`, which every row already calls, because the rows assign to the config objects directly
and nothing else can see a change happen.

Three decisions worth keeping:

- **Only settings with a control are stored.** Store one without, and its default is frozen on
  every device that ever ran the app — improving it later (as `subsampling` and `cursorShape` both
  were) would never reach an existing install, and there would be no control to undo it with.
- **`viewOnly` and the password are never stored.** View-only restored at startup is an app where
  nothing responds and nothing says why; the password lives in untracked `local.properties`
  precisely so it is not written down.
- **A saved address beats `vnc.dev.host`.** It has to, or the user's own address loses to a
  build-time default — so after a save, pointing a device somewhere else means the sheet (or
  clearing the app's data), not `local.properties`.

Until this existed nothing was kept at all: `VncConnectionConfig` starts from `BuildConfig`, empty
in a published build, so a typed-in address was gone at the next launch. It stayed unnoticed because
a development build has `vnc.dev.host` baked in, which on a developer's device looks exactly like
being remembered.
