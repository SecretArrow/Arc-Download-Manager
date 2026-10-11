#!/usr/bin/env python3
"""Generate Play Store-ready assets for Arc Download Manager (Pillow only).

Outputs:
  store/icon-512.png          512x512 full-bleed square (Play listing icon)
  store/feature-graphic.png   1024x500 banner (Play feature graphic / README hero)

Run: python3 tools/gen_play_assets.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "store"
SS = 4  # supersample factor for anti-aliasing

TOP = (26, 107, 255)     # #1A6BFF
BOTTOM = (11, 63, 168)   # #0B3FA8
WHITE = (255, 255, 255)


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    return img


def draw_logo(canvas, ox, oy, s):
    """Draw the Arc brand mark (108-viewport geometry) scaled by s and offset by (ox, oy)."""
    def P(x, y):
        return (ox + x * s, oy + y * s)

    stroke = int(7 * s)
    r = int(stroke / 2)
    d = ImageDraw.Draw(canvas)

    def line(p1, p2):
        d.line([p1, p2], fill=WHITE, width=stroke)
        for p in (p1, p2):
            d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=WHITE)

    # Arrow shaft + chevron head
    line(P(54, 32), P(54, 62))
    line(P(54, 62), P(40, 48))
    line(P(54, 62), P(68, 48))
    # Tray: left wall, rounded corner, bottom, rounded corner, right wall
    d.arc([*P(32, 64), *P(44, 76)], 90, 180, fill=WHITE, width=stroke)
    d.line([P(38, 76), P(70, 76)], fill=WHITE, width=stroke)
    d.arc([*P(64, 64), *P(76, 76)], 0, 90, fill=WHITE, width=stroke)
    d.line([P(32, 58), P(32, 70)], fill=WHITE, width=stroke)
    d.line([P(76, 58), P(76, 70)], fill=WHITE, width=stroke)
    for p in (P(32, 58), P(76, 58), P(38, 76), P(70, 76)):
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=WHITE)


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
    img = vertical_gradient((size, size), TOP, BOTTOM)
    draw_logo(img, ox=0, oy=0, s=size / 108.0)
    # Content is centered by the 108-viewport safe zone; add a subtle bottom glow band
    img = img.resize((512, 512), Image.LANCZOS)
    img.save(OUT / "icon-512.png", optimize=True)
    print("wrote", OUT / "icon-512.png")


def make_feature_graphic():
    w, h = 1024 * SS, 500 * SS
    img = vertical_gradient((w, h), TOP, BOTTOM)
    d = ImageDraw.Draw(img)
    # Decorative arcs (brand motif)
    for i, rr in enumerate((int(h * 0.95), int(h * 0.75))):
        d.arc([int(w * 0.62) - rr, h // 2 - rr, int(w * 0.62) + rr, h // 2 + rr],
              200, 340, fill=(255, 255, 255, 30), width=3 * SS)
    draw_logo(img, ox=w * 0.055, oy=h * 0.5 - (108 * (h / 500) * 1.35) / 2, s=(h / 500) * 1.35 / (108 / 108))

    title_font = load_font(64 * SS)
    tag_font = load_font(26 * SS)
    tx = int(w * 0.34)
    max_w = w - tx - 24 * SS
    # Shrink title until it fits the available width
    while d.textlength("Arc Download Manager", font=title_font) > max_w:
        title_font = ImageFont.truetype(title_font.path, title_font.size - 2 * SS) if hasattr(title_font, "path") else load_font(title_font.size - 2 * SS)
    d.text((tx, int(h * 0.34)), "Arc Download Manager", font=title_font, fill=WHITE)
    d.text((tx, int(h * 0.58)), "Fast  •  Segmented  •  Reliable", font=tag_font,
           fill=(219, 234, 254))
    img = img.resize((1024, 500), Image.LANCZOS)
    img.save(OUT / "feature-graphic.png", optimize=True)
    print("wrote", OUT / "feature-graphic.png")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    make_icon()
    make_feature_graphic()
