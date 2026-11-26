#!/usr/bin/env python3
"""Generate the half-sourceG100 launcher icon (res/mipmap-*/ic_launcher.png).

A dark rounded square with an orange one-half glyph: 'half' of a Source engine.
Run from anywhere: python3 scripts/gen_icon.py
"""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
GLYPH = "\u00bd"  # ½ (one half)
BG = (16, 18, 22, 255)
FG = (255, 122, 24, 255)

for name, px in SIZES.items():
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = max(4, px // 5)
    d.rounded_rectangle([0, 0, px - 1, px - 1], radius=r, fill=BG,
                        outline=FG, width=max(2, px // 48))
    f = ImageFont.truetype(FONT, int(px * 0.60))
    bbox = d.textbbox((0, 0), GLYPH, font=f)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text(((px - w) / 2 - bbox[0], (px - h) / 2 - bbox[1]), GLYPH, font=f, fill=FG)
    out = os.path.join(ROOT, "android-app", "res", f"mipmap-{name}")
    os.makedirs(out, exist_ok=True)
    img.save(os.path.join(out, "ic_launcher.png"))
    print("wrote", out)
