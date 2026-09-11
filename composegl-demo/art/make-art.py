#!/usr/bin/env python3
"""Draws the demo's nine-patch art and writes the atlas that describes it.

The art is generated rather than drawn by hand so the repository carries the source of the
pictures, not just the pictures. Run it from the repository root:

    python3 composegl-demo/art/make-art.py

It writes ui.png and ui.atlas into the demo's resources.

The one rule that matters when drawing for a nine-patch: whatever is going to be stretched must be
uniform along the axis it stretches on. A highlight that runs from x=13 to x=34 looks like a nice
touch of light in the art and becomes a hard bar across the whole panel the moment the panel is
600 pixels wide. So the top band here is a gradient that is the same all the way across, and the
hatch on the ribbon repeats with exactly the period of the cell it lives in.
"""

from PIL import Image, ImageDraw
import pathlib

OUT = pathlib.Path("composegl-demo/src/main/resources/ui")
ATLAS_W, ATLAS_H = 128, 64

INK = (21, 27, 38, 236)
EDGE = (58, 70, 92, 255)
ACCENT = (76, 194, 255)


def over(base, overlay):
    return Image.alpha_composite(base, overlay)


def layer(size):
    """A transparent sheet to draw on, so that alpha composites instead of replacing."""
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def panel():
    """A 48x48 bevelled box: 16-pixel corners, a lit top edge, a hairline inside the frame."""
    image, draw = layer((48, 48))
    draw.rounded_rectangle((0, 0, 47, 47), radius=12, fill=INK)

    # The shape, as a mask, so the light stops at the rounded corners.
    mask, cut = layer((48, 48))
    cut.rounded_rectangle((0, 0, 47, 47), radius=12, fill=(255, 255, 255, 255))

    # Light from above: a gradient that has faded out by the time it reaches the stretching row,
    # and that is the same at every x, so widening the panel cannot show a seam.
    glow, paint = layer((48, 48))
    for y in range(2, 16):
        paint.line((0, y, 47, y), fill=(190, 220, 255, int(26 * (1 - (y - 2) / 14))))
    glow.putalpha(Image.composite(glow.getchannel("A"), Image.new("L", (48, 48), 0), mask.getchannel("A")))
    image = over(image, glow)

    frame, pen = layer((48, 48))
    pen.rounded_rectangle((0, 0, 47, 47), radius=12, outline=EDGE, width=2)
    pen.rounded_rectangle((3, 3, 44, 44), radius=9, outline=ACCENT + (46,), width=1)
    return over(image, frame)


def ribbon():
    """A 24x24 header band: slanted end caps, and a hatch in the middle that is built to repeat."""
    image, draw = layer((24, 24))

    # The band, with a cut corner at each end. Ten pixels of cap at top and bottom are kept by the
    # split; only the four rows between them stretch.
    draw.polygon([(5, 2), (23, 2), (23, 18), (18, 21), (0, 21), (0, 6)], fill=ACCENT + (40,))
    draw.line((5, 2, 23, 2), fill=ACCENT + (160,))
    draw.line((0, 21, 18, 21), fill=ACCENT + (90,))
    draw.line((0, 6, 5, 2), fill=ACCENT + (160,))
    draw.line((18, 21, 23, 18), fill=ACCENT + (90,))

    # A diagonal hatch across the middle eight columns. Written as (x + y) mod 8 rather than as
    # drawn lines, because that is periodic in x with the period of the cell — which is the whole
    # requirement for a tile that does not show its joins.
    hatch, _ = layer((24, 24))
    pixels = hatch.load()
    for y in range(3, 21):
        for x in range(8, 16):
            if (x + y) % 8 < 2:
                pixels[x, y] = (170, 225, 255, 30)
    inside = image.getchannel("A").point(lambda a: 255 if a > 0 else 0)
    hatch.putalpha(Image.composite(hatch.getchannel("A"), Image.new("L", (24, 24), 0), inside))
    return over(image, hatch)


def crest():
    """A 24x24 emblem. Not a nine-patch: a picture, drawn at whatever size it is asked for."""
    image, draw = layer((24, 24))

    # A diamond with a notch out of the bottom, and a bar across it. Nothing here is stretched, so
    # it can have detail in the middle that a nine-patch could not.
    draw.polygon([(12, 1), (22, 7), (22, 15), (12, 23), (2, 15), (2, 7)], fill=ACCENT + (54,))
    draw.line([(12, 1), (22, 7), (22, 15), (12, 23), (2, 15), (2, 7), (12, 1)], fill=ACCENT + (200,))
    draw.line((7, 11, 17, 11), fill=ACCENT + (200,))
    draw.polygon([(12, 6), (16, 11), (12, 17), (8, 11)], fill=(232, 244, 255, 120))
    return image


def main():
    atlas = Image.new("RGBA", (ATLAS_W, ATLAS_H), (0, 0, 0, 0))
    atlas.paste(panel(), (0, 0))
    atlas.paste(ribbon(), (52, 0))
    atlas.paste(crest(), (80, 0))

    OUT.mkdir(parents=True, exist_ok=True)
    atlas.save(OUT / "ui.png")

    # split and pad are both written left, right, top, bottom. The panel's pad is bigger than its
    # split on purpose: the contents should clear the bevel, not sit on it.
    (OUT / "ui.atlas").write_text(
        "\n".join(
            [
                "ui.png",
                f"size:{ATLAS_W},{ATLAS_H}",
                "format:RGBA8888",
                "filter:Linear,Linear",
                "repeat:none",
                "panel",
                "  bounds:0,0,48,48",
                "  split:16,16,16,16",
                "  pad:20,20,18,18",
                "ribbon",
                "  bounds:52,0,24,24",
                "  split:8,8,10,10",
                "  pad:14,14,5,6",
                # No split and no pad: a picture, not a frame. The Image widget scales it.
                "icon/crest",
                "  bounds:80,0,24,24",
            ]
        )
        + "\n"
    )
    print(f"wrote {OUT / 'ui.png'} and {OUT / 'ui.atlas'}")


if __name__ == "__main__":
    main()
