"""Validates the generated Play Store assets against Play's upload rules and against clipping.

Run after generate_store_assets.py. This exists because the two ways these assets break are
invisible in a diff:

  - Play rejects an RGBA PNG at upload time, with a message that does not name the file. Cheap to
    check here instead of discovering it after a release is drafted.
  - Text or a mockup can overflow its box. The generator wraps text, but only the rendered pixels
    know whether the wrap actually worked, and a screenshot with a headline running off the edge
    still looks fine in a file listing.

Content checks are assertions about the images, not about the source that produced them, so this
keeps working if the generator is rewritten.

Usage:  python tools/store/validate_store_assets.py
"""

from __future__ import annotations

import glob
import os
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install Pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "store"))
SCREENSHOT_DIR = os.path.join(OUT_DIR, "screenshots", "phone")
FEATURE_GRAPHIC = os.path.join(OUT_DIR, "feature_graphic.png")

PHONE_W, PHONE_H = 1080, 1920
FEATURE_W, FEATURE_H = 1024, 500

# Play's phone rules: 16:9 or 9:16, between 320px and 3840px on the long edge.
ALLOWED_PHONE_RATIOS = [(16, 9), (9, 16)]

# Marketing copy is white on a dark canvas; anything dimmer than this fails WCAG AA for large
# text and reads as a rendering bug in the listing grid.
MIN_LUMA_FOR_TEXT = 140

failures: list[str] = []
checks = 0


def check(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        failures.append(message)


def luma(pixel: tuple[int, int, int]) -> float:
    r, g, b = pixel[:3]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def validate_phone(path: str) -> None:
    name = os.path.basename(path)
    with Image.open(path) as img:
        w, h = img.size
        check(img.mode == "RGB", "{}: mode is {} not RGB (Play rejects alpha)".format(name, img.mode))
        check(min(w, h) >= 320, "{}: short edge {} below Play's 320px minimum".format(name, min(w, h)))
        check(max(w, h) <= 3840, "{}: long edge {} above Play's 3840px maximum".format(name, max(w, h)))

        ratio_ok = False
        for num, den in ALLOWED_PHONE_RATIOS:
            if abs((w / h) - (num / den)) < 0.01:
                ratio_ok = True
        check(
            ratio_ok,
            "{}: {}x{} is not 16:9 or 9:16; Play rejects other phone ratios".format(name, w, h),
        )

        rgb = img.convert("RGB")
        # Content must actually be there: a flat fill means the generator silently no-opped.
        colours = rgb.resize((160, 284)).getcolors(maxcolors=160 * 284)
        check(colours is not None and len(colours) > 40, "{}: too few distinct colours; looks blank".format(name))

        # Nothing should touch the outer 40px. The generator insets all content by 64px, so any
        # non-background pixel out here means text or a panel escaped its box.
        ink = count_bright(rgb, 0, 0, w, 40)
        check(ink == 0, "{}: {} bright pixels in the top margin (clipped content?)".format(name, ink))

        ink = count_bright(rgb, 0, h - 40, w, h)
        check(ink == 0, "{}: {} bright pixels in the bottom margin (clipped content?)".format(name, ink))

        ink = count_bright(rgb, 0, 0, 40, h)
        check(ink == 0, "{}: {} bright pixels in the left margin (clipped content?)".format(name, ink))

        ink = count_bright(rgb, w - 40, 0, w, h)
        check(ink == 0, "{}: {} bright pixels in the right margin (clipped content?)".format(name, ink))

        # The headline band must contain genuinely bright text, not a washed-out placeholder.
        brightest = max_luma(rgb.crop((96, 214, w - 96, 420)))
        check(
            brightest >= MIN_LUMA_FOR_TEXT,
            "{}: brightest headline pixel luma {:.0f} below {}".format(name, brightest, MIN_LUMA_FOR_TEXT),
        )

        # The mockup panel must not be empty.
        panel = rgb.crop((64, 880, w - 64, 1660))
        panel_colours = panel.resize((120, 100)).getcolors(maxcolors=120 * 100)
        check(
            panel_colours is not None and len(panel_colours) > 12,
            "{}: mockup panel looks empty".format(name),
        )


def count_bright(img: Image.Image, x0: int, y0: int, x1: int, y1: int, threshold: int = 90) -> int:
    # convert("L") applies the luma transform, and the histogram counts in C rather than
    # iterating millions of pixels in Python. Both matter: this runs over every margin of
    # every image, and the pixel-loop version was an order of magnitude slower and emitted a
    # Pillow deprecation warning on every run.
    return sum(img.crop((x0, y0, x1, y1)).convert("L").histogram()[threshold + 1:])


def max_luma(img: Image.Image) -> float:
    return float(img.convert("L").getextrema()[1])


def validate_feature_graphic(path: str) -> None:
    name = os.path.basename(path)
    with Image.open(path) as img:
        w, h = img.size
        check(
            (w, h) == (FEATURE_W, FEATURE_H),
            "{}: {}x{} but Play requires exactly 1024x500".format(name, w, h),
        )
        check(img.mode == "RGB", "{}: mode is {} not RGB (Play rejects alpha)".format(name, img.mode))

        rgb = img.convert("RGB")
        colours = rgb.resize((160, 78)).getcolors(maxcolors=160 * 78)
        check(colours is not None and len(colours) > 30, "{}: looks blank".format(name))

        brightest = max_luma(rgb.crop((64, 92, 700, 220)))
        check(brightest >= MIN_LUMA_FOR_TEXT, "{}: title luma {:.0f} too low".format(name, brightest))


def main() -> None:
    shots = sorted(glob.glob(os.path.join(SCREENSHOT_DIR, "*.png")))
    check(len(shots) >= 2, "Play requires at least 2 screenshots; found {}".format(len(shots)))
    check(os.path.isfile(FEATURE_GRAPHIC), "feature_graphic.png is missing")

    for shot in shots:
        validate_phone(shot)
    if os.path.isfile(FEATURE_GRAPHIC):
        validate_feature_graphic(FEATURE_GRAPHIC)

    if failures:
        print("FAILED {} of {} checks:" .format(len(failures), checks))
        for failure in failures:
            print("  - " + failure)
        sys.exit(1)
    print("  all {} checks passed across {} screenshots + feature graphic".format(checks, len(shots)))


if __name__ == "__main__":
    main()