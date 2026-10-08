#!/usr/bin/env python3
"""Renders the Hot Mess launcher icons into app/src/*/res.

The same mark as the iOS app (hot_mess_ios Design/AppIcon): the "misprint", the figure from the
original login background (silhouette.svg) in ink with two offset copies slipping out of register
behind it. Each build has its own colour, as the 2017 icons did:

  main     Production   blue    (white paper, blue and pink inks)
  staging  Test         green
  debug    Development  purple

Android gets an adaptive icon (paper background, inks and figure in the foreground, the figure alone
as the monochrome layer for themed icons) and legacy square and round PNGs.

  python3 design/make_launcher_icons.py          # from the repository root

Requires cairosvg and Pillow.
"""

from __future__ import annotations

import io
import re
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent

INK = "#1a1519"  # hot_mess_dark surface

# Adaptive icon layers are 108dp; launchers show the middle 72dp and keep the middle 66dp.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
LAYER_DP = 108
VISIBLE_DP = 72
LEGACY_DP = 48

# The iOS artwork is drawn on a 1024 canvas; here that canvas fills the visible 72dp and the figure
# carries on into the 18dp margins, so it still runs off the bottom when the launcher moves the layer.
CANVAS = 1024
MARGIN = CANVAS * (LAYER_DP - VISIBLE_DP) / 2 / VISIBLE_DP
PLACEMENT = "scale(0.80) translate(140 -470)"
OFFSET = (30, 14)

BUILDS = {
    "main": dict(paper="#f6eff3", left="#2fb4ff", right="#ff3d9a"),
    "staging": dict(paper="#c9efc6", left="#ffd23d", right="#2fbf4f"),
    "debug": dict(paper="#dcc0f5", left="#a46bff", right="#5e1a9c"),
}


def figure_path():
    svg = (HERE / "silhouette.svg").read_text()
    return re.search(r'<path id="figure" d="([^"]+)"', svg).group(1)


def layer_svg(layers):
    """`layers` is a list of (fill, dx, dy) copies of the figure, back to front."""
    d = figure_path()
    side = CANVAS + 2 * MARGIN
    body = "".join(
        f'<g transform="translate({dx} {dy})"><path transform="{PLACEMENT}" d="{d}" fill="{fill}"/></g>'
        for fill, dx, dy in layers
    )
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{-MARGIN} {-MARGIN} {side} {side}">{body}</svg>'


def rasterise(svg, size):
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def render(build, colours):
    res = ROOT / "app" / "src" / build / "res"
    dx, dy = OFFSET
    foreground_svg = layer_svg([(colours["left"], -dx, -dy), (colours["right"], dx, dy), (INK, 0, 0)])
    monochrome_svg = layer_svg([("#ffffff", 0, 0)])
    for density, scale in DENSITIES.items():
        layer = round(LAYER_DP * scale)
        mipmap = res / f"mipmap-{density}"
        mipmap.mkdir(parents=True, exist_ok=True)

        background = Image.new("RGBA", (layer, layer), colours["paper"])
        foreground = rasterise(foreground_svg, layer)
        monochrome = rasterise(monochrome_svg, layer)

        background.convert("RGB").save(mipmap / "ic_launcher_background.png", optimize=True)
        foreground.save(mipmap / "ic_launcher_foreground.png", optimize=True)
        monochrome.save(mipmap / "ic_launcher_monochrome.png", optimize=True)

        # Legacy icons (before Android 8 there's no adaptive icon; minSdk is 26, but some launchers
        # still ask for these): the visible middle of the layers.
        full = background.copy()
        full.alpha_composite(foreground)
        crop = round(layer * (LAYER_DP - VISIBLE_DP) / 2 / LAYER_DP)
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
    for build, colours in BUILDS.items():
        render(build, colours)
