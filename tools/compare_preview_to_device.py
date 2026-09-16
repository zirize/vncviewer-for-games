#!/usr/bin/env python3
"""Checks that the preview SVG and the real device screen agree on where the buttons are.

🔑 Why it is needed: the preview uses the same layoutPanel arithmetic the app does, but it cannot
   know what happens *after* that - margin computation, aspect ratio, device scaling. The moment
   the preview quietly drifts, it becomes a tool that lies. This catches that moment.

🔴 No SVG rasteriser is needed: it only compares button coordinates, so it runs anywhere.

Usage:
    ./gradlew :app:previewProfiles                       # render the SVGs
    adb exec-out screencap -p > /tmp/shot.png            # capture the device
    python3 tools/compare_preview_to_device.py \\
        app/build/preview/default.svg /tmp/shot.png      # compare

Exit code: 0 = every button was found on screen, 1 = at least one did not line up
"""
import re
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: pip install pillow")

# 🔑 The preview prints each id 20px below its button, which is how the button position is recovered.
ID_LABEL = re.compile(r'<text x="([\d.]+)" y="([\d.]+)" fill="#7d99ad"[^>]*>([^<]+)</text>')

# Buttons are a white outline over 60% black, so there must be bright pixels where one is.
WHITE = 200
MIN_WHITE_PIXELS = 30


def main(svg_path, shot_path):
    svg = open(svg_path, encoding="utf-8").read()
    shot = Image.open(shot_path).convert("L")

    m = re.search(r'<svg[^>]*width="(\d+)" height="(\d+)"', svg)
    if not m:
        sys.exit("could not read the size out of the SVG")
    sw, sh = int(m.group(1)), int(m.group(2))
    if (sw, sh) != shot.size:
        sys.exit(f"size mismatch - SVG {sw}x{sh} vs screen {shot.size[0]}x{shot.size[1]}.\n"
                 f"  🔑 The preview is drawn in the authored space (2400x1080). On a device with a "
                 f"different resolution this comparison means nothing - it would have to be "
                 f"redrawn for that device.")

    labels = ID_LABEL.findall(svg)
    if not labels:
        sys.exit("no button id labels in the SVG - has the preview renderer changed?")

    missing = []
    for sx, sy, bid in labels:
        cx, by = float(sx), float(sy) - 20      # the label sits 20px below the button
        box = (int(cx - 60), int(by - 60), int(cx + 60), int(by - 4))
        white = sum(1 for p in shot.crop(box).tobytes() if p > WHITE)
        if white < MIN_WHITE_PIXELS:
            missing.append((bid, white))

    print(f"{len(labels)} buttons, {len(missing)} not found on screen")
    for bid, n in missing:
        print(f"  🔴 {bid}: only {n} bright pixels there - preview and device disagree")
    return 1 if missing else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
