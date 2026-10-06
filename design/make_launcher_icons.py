#!/usr/bin/env python3
"""Renders the Hot Mess launcher icons into app/src/*/res.

The same mark as the iOS app (hot_mess_ios Design/AppIcon): the silhouette from the original Hot Mess
icon, recoloured with the AudienceKit `hot_mess` theme. Android gets an adaptive icon (an accent
gradient behind the ink silhouette, plus a monochrome layer for themed icons) and legacy square and
round PNGs. Debug and staging builds carry a DEV or STAGING label, like the iOS ribbons.

  python3 design/make_launcher_icons.py          # from the repository root

Requires Pillow.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
FONT = ROOT / "app" / "src" / "main" / "res" / "font" / "figtree.ttf"

ACCENT_TOP = (0xD6, 0x3A, 0x8A)  # accent, lifted
ACCENT = (0xB8, 0x23, 0x6F)  # hot_mess accent
ACCENT_DEEP = (0x6E, 0x10, 0x40)  # accent, deepened
INK = (0x1A, 0x15, 0x19)  # hot_mess_dark surface

# Adaptive icon layers are 108dp; launchers show the middle 72dp and keep the middle 66dp.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
LAYER_DP = 108
LEGACY_DP = 48
FIGURE_SCALE = 0.85  # the silhouette's 1024px canvas, as a share of the layer

BUILDS = {"main": None, "debug": "DEV", "staging": "STAGING"}


def gradient(size):
    image = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(image)
    stops = [(0, ACCENT_TOP), (0.55, ACCENT), (1, ACCENT_DEEP)]
    for y in range(size):
        t = y / (size - 1)
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if p0 <= t <= p1:
                u = (t - p0) / (p1 - p0)
                draw.line([(0, y), (size, y)], fill=tuple(round(a + (b - a) * u) for a, b in zip(c0, c1)))
                break
    return image


def figure_mask(size):
    """The silhouette, bottom-centred on a transparent layer of `size` px."""
    mask = Image.open(HERE / "silhouette-mask.png").convert("L")
    side = round(size * FIGURE_SCALE)
    mask = mask.resize((side, side), Image.LANCZOS)
    layer = Image.new("L", (size, size), 0)
    layer.paste(mask, ((size - side) // 2, size - side))
    return layer


def label(image, text, size, fill, ink):
    """A pill naming the build, inside the safe zone."""
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(FONT), round(size * 0.075))
    try:
        font.set_variation_by_axes([800])
    except OSError:
        pass
    box = draw.textbbox((0, 0), text, font=font)
    width, height = box[2] - box[0], box[3] - box[1]
    pad_x, pad_y = size * 0.03, size * 0.018
    centre_y = size * 0.74
    rect = [
        (size - width) / 2 - pad_x,
        centre_y - height / 2 - pad_y,
        (size + width) / 2 + pad_x,
        centre_y + height / 2 + pad_y,
    ]
    draw.rounded_rectangle(rect, radius=(rect[3] - rect[1]) / 2, fill=fill)
    draw.text(((size - width) / 2 - box[0], centre_y - height / 2 - box[1]), text, font=font, fill=ink)


def render(build, tag):
    res = ROOT / "app" / "src" / build / "res"
    for density, scale in DENSITIES.items():
        layer = round(LAYER_DP * scale)
        mipmap = res / f"mipmap-{density}"
        mipmap.mkdir(parents=True, exist_ok=True)

        background = gradient(layer)
        foreground = Image.new("RGBA", (layer, layer), (0, 0, 0, 0))
        foreground.paste(Image.new("RGBA", (layer, layer), INK + (255,)), (0, 0), figure_mask(layer))
        monochrome = Image.new("RGBA", (layer, layer), (0, 0, 0, 0))
        monochrome.paste(Image.new("RGBA", (layer, layer), (255, 255, 255, 255)), (0, 0), figure_mask(layer))
        if tag:
            label(foreground, tag, layer, INK + (255,), (255, 255, 255, 255))
            label(monochrome, tag, layer, (255, 255, 255, 255), (0, 0, 0, 0))

        background.save(mipmap / "ic_launcher_background.png", optimize=True)
        foreground.save(mipmap / "ic_launcher_foreground.png", optimize=True)
        monochrome.save(mipmap / "ic_launcher_monochrome.png", optimize=True)

        # Legacy icons (before Android 8 there's no adaptive icon; minSdk is 26, but some launchers
        # still ask for these): the visible middle of the layers.
        full = background.convert("RGBA")
        full.alpha_composite(foreground)
        crop = round(layer * 18 / LAYER_DP)
        full = full.crop((crop, crop, layer - crop, layer - crop))
        legacy = round(LEGACY_DP * scale)
        full = full.resize((legacy, legacy), Image.LANCZOS)

        square = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
        square_mask = Image.new("L", (legacy, legacy), 0)
        ImageDraw.Draw(square_mask).rounded_rectangle([0, 0, legacy - 1, legacy - 1], radius=legacy * 0.18, fill=255)
        square.paste(full, (0, 0), square_mask)
        square.save(mipmap / "ic_launcher.png", optimize=True)

        round_icon = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
        round_mask = Image.new("L", (legacy, legacy), 0)
        ImageDraw.Draw(round_mask).ellipse([0, 0, legacy - 1, legacy - 1], fill=255)
        round_icon.paste(full, (0, 0), round_mask)
        round_icon.save(mipmap / "ic_launcher_round.png", optimize=True)

    anydpi = res / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(xml)
    (anydpi / "ic_launcher_round.xml").write_text(xml)


if __name__ == "__main__":
    for build, tag in BUILDS.items():
        render(build, tag)
