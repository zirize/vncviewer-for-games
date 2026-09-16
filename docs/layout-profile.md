# The layout profile format

A profile is one JSON file describing every on-screen button. `profiles/default.json` is the one
that ships; anything else you drop in `profiles/` is built with
`./gradlew :app:assembleRelease -PvncProfile=<name>.json`.

Parsed by `OverlayProfileParser` (plain JVM, no Android), checked by `OverlayProfileValidator`.

---

## Units: authored pixels

Coordinates are **pixels in the space the layout was drawn in**, not dp.

```json
"authoredForMarginPx": 240,
"authoredForHeightPx": 1080,
```

At runtime the panel width is derived from the *device screen height* (`height × 240/1080`), and the
whole panel is scaled by `real panel width ÷ authoredForMarginPx`. One scale for the entire panel —
never per button, because per-button scaling destroys the spacing and buttons start to touch.

> **Why not dp?** The older design note is written in dp and converting loses the layout: 114px ÷
> 2.625 = 43.43dp, and 43dp back is 112.875px. Every button would shift a pixel or two.

`x` is measured from the **outer screen edge** of that panel — the left edge for `"panel":"left"`,
the right edge for `"panel":"right"`. That way buttons stay glued to the edge when the margin
changes width, instead of drifting toward the middle of the screen.

`y` is measured from the top for `"anchor":"top"` and **from the bottom** for `"anchor":"bottom"`.
Bottom anchoring is what keeps the settings button on screen on a taller device.

---

## Top level

| field | meaning |
|---|---|
| `schema` | integer, currently `1`. A **larger** value than the app knows is refused outright |
| `id` | identifier; also the name of the generated SVG preview |
| `name` | human-readable name |
| `origin` | `"builtin"` for the ones in this repo |
| `authoredForMarginPx` | panel width these coordinates assume |
| `authoredForHeightPx` | screen height these coordinates assume |
| `cornerRadiusPx` | fixed corner radius, **not** proportional to button size |
| `buttons` | array, see below |

Keys starting with `_comment` are ignored, and `default.json` uses them to keep the reasoning next
to the data.

> **Corner radius is fixed on purpose.** Make it proportional and small buttons turn into circles
> while long ones turn into pills, and the panel stops looking like one family.

---

## A button

```json
{
  "id": "esc",
  "label": "ESC",
  "shape": "rounded",
  "panel": "right",
  "anchor": "top",
  "x": 41, "y": 404, "w": 105, "h": 56,
  "action": { "type": "key", "keysym": "Escape", "behavior": "tap" }
}
```

| field | values |
|---|---|
| `id` | unique within the profile; duplicates are an error |
| `label` | text drawn on the button, or `null` |
| `shape` | `circle` · `rounded` · `mouse` · `dpad` · `icon` |
| `panel` | `left` · `right` |
| `anchor` | `top` · `bottom` |
| `x` `y` `w` `h` | authored pixels |
| `action` | see below |

`mouse` draws a rounded body with two small buttons on top, like a mouse seen from above. **The
decoration is not touchable** — only the body is.

`dpad` is deliberately **one button** with four directions resolved inside it. Four separate
buttons would each get expanded to a 48dp touch target, those targets would overlap, and up and
left would fire together.

### Drawn size is not touched size

`w`/`h` are what gets drawn. Touch targets are expanded to a minimum of 48dp, so at the default
spacing (43.4dp buttons, 4.6dp gaps) **they necessarily overlap**. Where they overlap, the button
whose *centre* is nearer wins, splitting the overlapping band down the middle.

---

## Actions

| `type` | fields | effect |
|---|---|---|
| `key` | `keysym`, `behavior` | one key |
| `dpad` | `up` `down` `left` `right` | four directions, held while touched |
| `wheel` | `direction` (`up`/`down`), `clicks` | wheel; RFB has no wheel event, so a button bit is pressed `clicks` times |
| `mouse` | `button` (`left`/`right`/`middle`) | a mouse click |
| `ui` | `command` (`settings`) | opens the app's own screen; sends nothing to the server |

`behavior` is `tap` (press and release), `hold` (down while touched), or `latch` (tap = one-shot,
tap twice = locked).

`latch` only means anything on a modifier. On anything else the parser downgrades it to `tap` and
records a warning rather than rejecting the file — one less clever button beats a profile that will
not open at all.

### 🔴 `label` and `keysym` are different things

A button labelled `F` must send lowercase `f` (0x66). Uppercase `F` in X11 is *F with Shift held*.
Send the uppercase one and it compiles, it draws, the screen looks right, and the game simply does
not react. The validator rejects any keysym in `A`–`Z` for exactly this reason.

`keysym` accepts three spellings:

- a constant name from `VncKeySym` — `"PageUp"`, `"ControlL"`, `"Escape"`, `"Return"`, `"F5"` …
- a single character — `"f"`, `"h"`, `"v"`
- a number — `"0xFF55"` or `65365`

`input/VncKeys.kt` is the one registry; there is no second table to keep in sync.

---

## When a profile is wrong

Two grades, because "the overlay did not appear" is the worst possible outcome:

| what | happens |
|---|---|
| bad JSON, unknown larger `schema`, no buttons | the whole file is refused; the app opens a **rescue profile containing only the settings button** |
| one button's `action` is unreadable | **only that button** is disabled — still drawn, but dimmed and inert |

A disabled button is drawn rather than hidden because a button that vanishes reads as "I deleted
it by accident" instead of "something is wrong".

---

## What the validator enforces

| code | |
|---|---|
| `no-settings-exit` | 🔴 no button opens settings — the user would be locked out |
| `uppercase-keysym` | 🔴 a keysym in `A`–`Z` |
| `out-of-panel` | 🔴 `x+w` exceeds the margin, or negative coordinates |
| `overlap` | 🔴 two buttons are drawn on top of each other |
| `duplicate-id` | 🔴 two buttons share an `id` |
| `unreadable` | 🔴 the parser could not read something |
| `latch-on-non-modifier` | ⚠️ downgraded to `tap` |
| `tiny` | ⚠️ drawn too small to see |

Run it with `./gradlew :app:previewProfiles`.

Separately, `checkSelectedProfile` runs as part of every build and refuses just one thing — a
profile with no settings exit. It is deliberately narrow: that is the only mistake a user cannot
recover from, and a heavy gate is a gate people learn to disable.
