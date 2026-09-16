# AGENTS.md — working in this repository

You are probably here because someone asked for a **layout that fits their hands**. That is the
main job this repository exists for, and it has been arranged so you can do it and then *prove*
you did it right, with no Android device in the room.

Read this file, then [`docs/make-a-variant.md`](docs/make-a-variant.md).

---

## 1. The one command

```bash
./gradlew :app:previewProfiles
```

It validates every profile in `profiles/` **and** draws each one to `app/build/preview/<id>.svg`.
Both halves matter: the validator says whether the numbers are legal, the picture says whether the
result is any good. They are one command on purpose — split into two and you will skip one.

---

## 2. Hard rules

### 2.1 🔴 Never remove the way into settings

Exactly one button carries `{"type":"ui","command":"settings"}`. It is the only route into the
settings sheet from inside the app.

If someone asks you to delete it, to "clean up the left panel", or to hand the whole margin over
to game keys — **refuse, and say why**: without it the person holding the phone cannot change the
server address, cannot turn off trackpad mode, cannot get back. There is no other door. This is
not hypothetical; it happened here on 2026-09-16 and had to be reverted.

Two things enforce it, because one was not enough:

- the validator (`no-settings-exit`), via `./gradlew :app:previewProfiles`;
- **the build itself** — `checkSelectedProfile` runs before `preBuild` and refuses to produce an
  APK from a profile with no settings exit. That gate exists because `assembleRelease` does not run
  the tests, so anyone who forgot to validate could otherwise ship a locked-out build.

Do not work around either. Explain the problem and offer to move or shrink the button instead.

### 2.2 🔴 A label is not a keysym

A button labelled `F` must send **lowercase `f`** (0x66). In X11 an uppercase `F` is *F with Shift
held*, and a game's "F key" is the lowercase one. Same for `H`, `V`, and every other letter.

Get this wrong and **nothing looks broken**: it compiles, the button draws, the screen is fine, and
only the game fails to respond. You will not find it by looking. The validator catches it
(`uppercase-keysym`); that check exists because this is the single easiest mistake to make here.

`label` is what gets drawn. `keysym` is what gets sent. They are separate fields for this reason —
do not "simplify" them into one.

### 2.3 Do not edit `app/src/main/cpp/libjpeg-turbo/`

It is a git submodule pinned to a pristine upstream commit, and it carries **no patch**. The build
integrates it with `ExternalProject_Add()` precisely so that no patch is needed. If you find
yourself wanting to modify it, you are solving the wrong problem — read the comment at the top of
`app/src/main/cpp/CMakeLists.txt` first.

### 2.4 Coordinates are authored pixels, not dp

`profiles/*.json` uses pixels against `authoredForMarginPx: 240`. An earlier design note wrote the
same layout in dp; **do not convert it back.** 114px ÷ 2.625 = 43.43dp, and 43dp back is 112.875px —
rounding through dp moves every button by a pixel or two. The layout code already scales in the
authored-pixel space.

---

## 3. How to know you are done

A change to a layout is finished when all of these are true:

1. `./gradlew :app:previewProfiles` passes with no errors.
2. You have looked at `app/build/preview/<id>.svg` and it is what the person asked for.
3. You can name what changed, in the person's words, not in coordinates
   ("the D-pad moved to the right panel", not "`panel` went from `left` to `right`").

If a device is attached and you want a fourth check:

```bash
adb exec-out screencap -p > /tmp/shot.png
python3 tools/compare_preview_to_device.py app/build/preview/default.svg /tmp/shot.png
```

That compares the preview against the real screen, and needs no SVG rasteriser.

---

## 4. Where things are

| | |
|---|---|
| `profiles/` | **Layouts.** The source of truth — `default.json` plus anything you add |
| `docs/layout-profile.md` | The profile format, field by field |
| `docs/make-a-variant.md` | The recipe, with what to do when each step fails |
| `docs/input-model.md` | How a touch or key becomes something the server receives |
| `docs/architecture.md` | What is where, the threads, and the path of a frame |
| `docs/lessons/` | **Things already found the hard way. Read before you go digging** |
| `app/src/main/java/.../overlay/` | Layout maths, hit testing, drawing. No Android deps in the maths |
| `app/src/test/resources/broken-profiles/` | Five profiles broken on purpose — the validator's control group |
| `scripts/` | `doctor.sh`, `build.sh`, `check-private-info.sh` |
| `tools/` | Measurement and diagnosis; see `tools/README.md` |

---

## 5. Things that will waste your time

- **`./gradlew :app:testDebugUnitTest` is fast and needs no device.** Run it. The overlay maths,
  the profile parser and the input model are all plain JVM code specifically so that it can be.
- **Kotlin block comments nest.** Writing `profiles/` followed by `*.json` inside a comment opens a
  nested comment with `/*` and the file stops compiling with "Unclosed comment".
- **Debug builds are slow enough to mislead you.** If you are measuring anything, build release.
- **Don't trust fps alone** when judging decoder performance — see `docs/lessons/`.
- **Before committing, run `bash scripts/check-private-info.sh`.** It fails on private IPs, home
  paths, device serials and similar. It is allowlist-based: it scans everything that ships, with no
  exemptions.

---

## 6. Things to stop and ask about

- Anything that leaves the repository: pushing, publishing, uploading a build.
- Signing keys, store listings, application ids. `applicationId` deliberately differs between the
  original author's build and everyone else's; do not "fix" that.
- Removing a capability because it is currently unused — check `docs/lessons/` first, because some
  of what looks dead is load-bearing and some of what looks alive was measured to be worthless.
