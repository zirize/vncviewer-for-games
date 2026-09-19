#!/usr/bin/env python3
"""Generates the Play Store graphics from the same geometry as the launcher icon.

  python3 tools/icon/make_store_assets.py --shots A.png B.png --out DIR

🔑 Why here and not by hand: the store icon must be the launcher icon, and the feature graphic
   must look like both. Drawing them separately is how a store listing ends up looking like a
   different product. Everything below comes from geometry.py.

🔴 Play's screenshot rules (read off the console 2026-09-19): 16:9 or 9:16, each side 320-3840 px,
   2-8 phone shots. Device captures here are 2400x1080 (20:9), which is **outside** that, so a raw
   capture cannot be uploaded. Rather than letterbox it into black bars, each capture is placed on
   a 1920x1080 board with a caption - which is what a store listing wants anyway.
"""
import argparse, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geometry as g
from PIL import Image, ImageDraw, ImageFont

BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
REG  = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"

def font(path, size):
    return ImageFont.truetype(path, size)

def gradient(w, h, top, bot, diagonal=False):
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    t = tuple(int(top[i:i+2], 16) for i in (1, 3, 5))
    b = tuple(int(bot[i:i+2], 16) for i in (1, 3, 5))
    n = (w + h) if diagonal else h
    for y in range(h):
        f = (y / max(h - 1, 1)) if not diagonal else (y / max(n - 1, 1))
        d.line([(0, y), (w, y)], fill=tuple(int(t[i] + (b[i] - t[i]) * f) for i in range(3)))
    return im

def _art(size):
    """The icon's foreground - screen, D-pad, buttons - on a transparent square."""
    SS = 4
    px = size * SS
    scale = px / g.SAFE
    def X(v): return (v - g.SAFE_MIN) * scale
    im = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    l, t, r, b = g.SCREEN
    d.rounded_rectangle([X(l), X(t), X(r), X(b)], radius=g.SCREEN_R * scale,
                        fill=g.SCREEN_FILL, outline=g.INK,
                        width=max(1, round(g.SCREEN_STROKE * scale)))
    for bar in g.dpad_bars():
        d.rounded_rectangle([X(bar[0]), X(bar[1]), X(bar[2]), X(bar[3])],
                            radius=1.4 * scale, fill=g.INK)
    for (cx, cy, rr) in g.buttons():
        d.ellipse([X(cx - rr), X(cy - rr), X(cx + rr), X(cy + rr)], fill=g.ACCENT)
    return im.resize((size, size), Image.LANCZOS)

def icon(size):
    """The store icon: **full-bleed square**, opaque.

    🔑 Play rounds the corners itself and shows it on its own backgrounds, so a rounded
       artwork with corners of our own would sit inside a visible square. Full bleed, no radius.
    """
    im = gradient(size, size, g.BG_TOP, g.BG_BOT).convert("RGBA")
    art = _art(size)
    im.alpha_composite(art)
    return im.convert("RGB")

def icon_rounded(size):
    """The same icon with its own rounded corners, transparent outside - for pasting onto art."""
    im = gradient(size, size, g.BG_TOP, g.BG_BOT).convert("RGBA")
    im.alpha_composite(_art(size))
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1],
                                           radius=int(size * 4 * 0.22), fill=255)
    im.putalpha(mask.resize((size, size), Image.LANCZOS))
    return im

def feature(title, tagline, sub, w=1024, h=500):
    """The 1024x500 feature graphic.

    🔑 The three strings are **arguments, not literals**. The published app name may be in a
       language this repository does not carry in its source (it is English-only outside
       res/values-ko/), and the listing copy is not the app's to decide. Whoever runs this passes
       the confirmed wording; the values live with the store listing, not here.
    """
    im = gradient(w, h, g.BG_TOP, g.BG_BOT)
    d = ImageDraw.Draw(im)
    s = 300
    ic = icon_rounded(s)
    im.paste(ic, (78, (h - s) // 2), ic)   # alpha, so the gradient shows through the corners
    x = 78 + s + 56
    d.text((x, 168), title, font=font(BOLD, 74), fill=g.INK)
    d.text((x, 262), tagline, font=font(REG, 36), fill=g.ACCENT)
    d.text((x, 316), sub, font=font(REG, 25), fill="#AEB8D0")
    return im

def board(shot_path, caption, sub, w=1920, h=1080):
    """One 16:9 screenshot board: the capture, plus a line saying what to look at."""
    im = gradient(w, h, g.BG_TOP, g.BG_BOT)
    d = ImageDraw.Draw(im)
    d.text((w // 2, 74), caption, font=font(BOLD, 52), fill=g.INK, anchor="mm")
    if sub:
        d.text((w // 2, 134), sub, font=font(REG, 30), fill="#AEB8D0", anchor="mm")

    shot = Image.open(shot_path).convert("RGB")
    tw = 1712
    th = round(shot.height * tw / shot.width)
    shot = shot.resize((tw, th), Image.LANCZOS)
    x, y = (w - tw) // 2, 196
    # 🔑 A hairline frame: without it a dark capture on a dark board has no edge and reads as a
    #    rendering fault rather than a screen.
    d.rectangle([x - 3, y - 3, x + tw + 2, y + th + 2], outline="#4A5878", width=3)
    im.paste(shot, (x, y))
    return im

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", nargs="*", default=[])
    ap.add_argument("--captions", nargs="*", default=[])
    ap.add_argument("--subs", nargs="*", default=[])
    ap.add_argument("--out", required=True)
    ap.add_argument("--title", default="RemotePad",
                    help="app name on the feature graphic - pass the name as published")
    ap.add_argument("--tagline", default="A VNC viewer for games")
    ap.add_argument("--sub", default="Big touch buttons in the screen's margins")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    p = os.path.join(a.out, "icon-512.png")
    icon(512).save(p); print("  ", p, "512x512")

    p = os.path.join(a.out, "feature-1024x500.png")
    feature(a.title, a.tagline, a.sub).save(p); print("  ", p, "1024x500")

    for i, sp in enumerate(a.shots):
        cap = a.captions[i] if i < len(a.captions) else ""
        sub = a.subs[i] if i < len(a.subs) else ""
        p = os.path.join(a.out, f"phone-{i+1}-1920x1080.png")
        board(sp, cap, sub).save(p)
        print("  ", p, "1920x1080", f"({os.path.basename(sp)})")

if __name__ == "__main__":
    main()
