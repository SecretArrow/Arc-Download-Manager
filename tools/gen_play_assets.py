#!/usr/bin/env python3
"""Generate Play Store-ready assets for Arc Download Manager (Pillow only).

Outputs:
  store/icon-512.png          512x512 full-bleed square (Play listing icon)
  store/feature-graphic.png   1024x500 banner (Play feature graphic / README hero)

Brand tokens mirror the launcher vectors:
  res/drawable/ic_launcher_background.xml  (indigo -> violet -> fuchsia diagonal)
  res/drawable/ic_launcher_foreground.xml  (cyan -> fuchsia arc ring + white arrow)

Run: python3 tools/gen_play_assets.py
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "store"
SS = 4  # supersample factor for anti-aliasing

# Brand tokens — must mirror res/drawable/ic_launcher_background.xml
BG_STOPS = [(0.0, (0x4F, 0x46, 0xE5)), (0.55, (0x7C, 0x3A, 0xED)), (1.0, (0xC0, 0x26, 0xD3))]
RING_STOPS = [(0.0, (0x22, 0xD3, 0xEE)), (1.0, (0xF0, 0xAB, 0xFC))]
WHITE = (255, 255, 255)

# Logo geometry — must mirror res/drawable/ic_launcher_foreground.xml (108 viewport)
VP = 108.0
CX = CY = 54.0
RING_R = 27.0
RING_W = 5.5
GAP = (240.0, 300.0)  # opening centered at top (270 deg, y-down clockwise)
ARROW_W = 7.5
SHAFT = ((54, 39.0), (54, 61.5))
CHEV = ((45, 52.5), (54, 61.5), (63, 52.5))
TRAY = ((44, 71.0), (64, 71.0))


def lerp_stops(stops, t):
    t = max(0.0, min(1.0, t))
    for i in range(len(stops) - 1):
        (t0, c0), (t1, c1) = stops[i], stops[i + 1]
        if t0 <= t <= t1:
            f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(int(round(a + (b - a) * f)) for a, b in zip(c0, c1))
    return stops[-1][1]


def diagonal_gradient(size, stops):
    """Linear gradient along the (0,0)->(w,h) diagonal, matching the SVG axis."""
    w, h = size
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    t = (xx / max(w - 1, 1) + yy / max(h - 1, 1)) / 2.0
    out = np.zeros((h, w, 3), np.uint8)
    for ch in range(3):
        pos = np.array([s[0] for s in stops], np.float32)
        vals = np.array([s[1][ch] for s in stops], np.float32)
        out[..., ch] = np.interp(t, pos, vals).astype(np.uint8)
    return Image.fromarray(out)


def draw_logo(canvas, ox, oy, s):
    """Draw the Arc brand mark (108-viewport geometry) scaled by s and offset by (ox, oy).

    Ring colors follow the same diagonal gradient as the vector foreground.
    """
    def P(x, y):
        return (ox + x * s, oy + y * s)

    def ring_color(x, y):
        # logo-local gradient param, same axis as the 108-viewport vector
        return lerp_stops(RING_STOPS, (x + y) / (2.0 * VP))

    d = ImageDraw.Draw(canvas)

    def cap(p, radius, color):
        r = radius * s
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=color)

    def seg(p1, p2, width, color):
        d.line([p1, p2], fill=color, width=max(1, int(round(width * s))))
        for p in (p1, p2):
            cap(p, width / 2.0, color)

    # Arc ring: 300-degree sweep with a 60-degree opening at the top, gradient stroke
    r_half = RING_W / 2.0
    a0, a1 = GAP[1], GAP[0] + 360.0  # clockwise from 300 deg through 0 to 240 deg
    a = a0
    while a <= a1:
        rad = math.radians(a)
        x = CX + RING_R * math.cos(rad)
        y = CY + RING_R * math.sin(rad)
        cap((P(x, y)), r_half, ring_color(x, y))
        a += 0.25

    # Download arrow + tray (white)
    stroke = ARROW_W
    seg(P(*SHAFT[0]), P(*SHAFT[1]), stroke, WHITE)
    seg(P(*CHEV[0]), P(*CHEV[1]), stroke, WHITE)
    seg(P(*CHEV[1]), P(*CHEV[2]), stroke, WHITE)
    seg(P(*TRAY[0]), P(*TRAY[1]), stroke, WHITE)


def load_font(size):
    for cand in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
    ):
        if Path(cand).exists():
            return ImageFont.truetype(cand, size)
    return ImageFont.load_default()


def make_icon():
    size = 512 * SS
    img = diagonal_gradient((size, size), BG_STOPS)
    draw_logo(img, ox=0, oy=0, s=size / VP)
    img = img.resize((512, 512), Image.LANCZOS)
    img.save(OUT / "icon-512.png", optimize=True)
    print("wrote", OUT / "icon-512.png")


def make_feature_graphic():
    w, h = 1024 * SS, 500 * SS
    img = diagonal_gradient((w, h), BG_STOPS)
    # Decorative arcs (brand motif), subtle translucent white
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for i, rr in enumerate((int(h * 0.95), int(h * 0.75))):
        od.arc([int(w * 0.62) - rr, h // 2 - rr, int(w * 0.62) + rr, h // 2 + rr],
               200, 340, fill=(255, 255, 255, 38), width=3 * SS)
    img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
    d = ImageDraw.Draw(img)
    # Brand mark: glyph box spans ~24..84 of the 108 viewport -> 0.50*h tall,
    # offset so the ring's right edge clears the text block
    s = 0.50 * h / 60.0
    draw_logo(img, ox=w * 0.035, oy=h / 2 - 54 * s, s=s)

    title_font = load_font(64 * SS)
    tag_font = load_font(26 * SS)
    tx = int(w * 0.365)
    max_w = w - tx - 24 * SS
    while d.textlength("Arc Download Manager", font=title_font) > max_w:
        title_font = ImageFont.truetype(title_font.path, title_font.size - 2 * SS) if hasattr(title_font, "path") else load_font(title_font.size - 2 * SS)
    d.text((tx, int(h * 0.33)), "Arc Download Manager", font=title_font, fill=WHITE)
    d.text((tx, int(h * 0.57)), "Fast  •  Segmented  •  HLS  •  Reliable", font=tag_font,
           fill=(221, 214, 254))
    img = img.resize((1024, 500), Image.LANCZOS)
    img.save(OUT / "feature-graphic.png", optimize=True)
    print("wrote", OUT / "feature-graphic.png")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    make_icon()
    make_feature_graphic()
