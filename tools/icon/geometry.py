"""The launcher icon's geometry, in one place.

🔑 Why a file and not two drawings: the icon exists twice - as vector XML (adaptive icon, API 26+)
   and as raster webp (legacy launchers, API 24-25, which this app still supports because
   minSdk = 24). Drawn twice by hand they drift, and the drift only shows on an old launcher
   that nobody here owns. So both are generated from these numbers.

The canvas is the adaptive-icon one: 108x108, of which the middle 72x72 is what a launcher is
guaranteed to show. Everything below therefore lives inside a circle of radius 33 centred on
(54, 54) - the strictest common mask.
"""

CANVAS = 108.0
SAFE = 72.0          # the inner square a launcher always shows
SAFE_MIN = 18.0      # (108 - 72) / 2

# ── The remote screen: the thing the app shows you ──
SCREEN = (28.0, 36.0, 80.0, 72.0)   # left, top, right, bottom
SCREEN_R = 6.0
SCREEN_STROKE = 3.0

# ── The D-pad, on the left, exactly where the app puts it ──
DPAD_C = (43.0, 54.0)
DPAD_ARM = 8.0        # centre to tip
DPAD_THICK = 6.4      # bar width

# ── The action buttons, on the right ──
BTN_C = (67.5, 54.0)
BTN_RING = 6.8        # centre to each button's centre
BTN_R = 3.3

# ── Colours ──
BG_TOP = "#2E4272"
BG_BOT = "#18203A"
SCREEN_FILL = "#0C1120"
INK = "#F2F6FC"       # frame and D-pad
ACCENT = "#F5B942"    # the action buttons

def dpad_bars():
    """The cross, as two rectangles (left, top, right, bottom)."""
    cx, cy = DPAD_C
    h = (cx - DPAD_ARM, cy - DPAD_THICK / 2, cx + DPAD_ARM, cy + DPAD_THICK / 2)
    v = (cx - DPAD_THICK / 2, cy - DPAD_ARM, cx + DPAD_THICK / 2, cy + DPAD_ARM)
    return h, v

def buttons():
    """The four action buttons, as (cx, cy, r) - top, right, bottom, left."""
    cx, cy = BTN_C
    return [
        (cx, cy - BTN_RING, BTN_R),
        (cx + BTN_RING, cy, BTN_R),
        (cx, cy + BTN_RING, BTN_R),
        (cx - BTN_RING, cy, BTN_R),
    ]
