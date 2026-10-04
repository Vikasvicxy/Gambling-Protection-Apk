"""Generates the Play Store listing images from the app's own brand palette.

Why a generator rather than checked-in binaries
-----------------------------------------------
Store screenshots have to change every time the UI, the palette or the marketing copy changes.
Keeping them as committed PNGs means the drift is invisible: the listing shows a card that no
longer exists and nobody notices until a user writes a one-star review about it. Generating them
makes the drift a failing build instead.

So the visuals are declared here, next to the copy they illustrate, and the output is written to
docs/store/. The colours below are the real ones from core/design-system Color.kt - not
re-derived, not eyeballed - so a palette change shows up in the listing images.

Pixel format
------------
Play accepts JPEG or *24-bit PNG with no alpha channel*. An RGBA PNG is silently rejected at
upload time, so every image is written through this module in RGB mode and the output is asserted
to be 24-bit. An alpha channel here would also mean the rounded phone frame blends against
whatever the listing background happens to be.

Dimensions
----------
Phone screenshots are 1080x1920 (9:16). The obvious modern choice of 1080x2400 is 9:20 and Play
rejects it: accepted phone aspect ratios are 16:9 and 9:16. 1080x1920 is also what the marketing
copy in docs/store/full_description.txt describes, so the images match the words.

The feature graphic is 1024x500, which Play requires exactly.

Usage:  python tools/store/generate_store_assets.py
"""

from __future__ import annotations

import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - developer environment issue, not a code path
    sys.exit("Pillow is required: pip install Pillow")

# --------------------------------------------------------------------------------------
# Palette, mirrored from core/design-system/src/.../Color.kt
# --------------------------------------------------------------------------------------

BLUE = (0x15, 0x65, 0xC0)
BLUE_DARK = (0x0D, 0x47, 0xA1)
BLUE_LIGHT = (0x42, 0xA5, 0xF5)
BLUE_SURFACE = (0xE3, 0xF2, 0xFD)

GREEN = (0x2E, 0x7D, 0x32)
GREEN_LIGHT = (0x81, 0xC7, 0x84)
ORANGE = (0xEF, 0x6C, 0x00)
RED = (0xC6, 0x28, 0x28)
WHITE = (0xFF, 0xFF, 0xFF)
OFF_WHITE = (0xF5, 0xF5, 0xF5)

# Deep neutral used as the canvas so white type has contrast in the listing grid.
INK = (0x0A, 0x12, 0x24)
INK_2 = (0x10, 0x1D, 0x38)
INK_3 = (0x18, 0x2B, 0x50)

PHONE_W, PHONE_H = 1080, 1920
FEATURE_W, FEATURE_H = 1024, 500

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "store")
SCREENSHOT_DIR = os.path.join(OUT_DIR, "screenshots", "phone")
FEATURE_DIR = os.path.join(OUT_DIR, "screenshots", "tablet")

# --------------------------------------------------------------------------------------
# Fonts
# --------------------------------------------------------------------------------------

_FONT_DIRS = [
    r"C:\Windows\Fonts",
    "/usr/share/fonts/truetype/dejavu",
    "/usr/share/fonts/truetype",
    "/System/Library/Fonts",
]

_REGULAR_CANDIDATES = ["segoeui.ttf", "DejaVuSans.ttf", "Helvetica.ttc", "Arial.ttf"]
_BOLD_CANDIDATES = ["segoeuib.ttf", "DejaVuSans-Bold.ttf", "Helvetica.ttc", "Arial Bold.ttf"]


def _find_font(candidates: list[str]) -> str:
    for directory in _FONT_DIRS:
        for name in candidates:
            path = os.path.join(directory, name)
            if os.path.isfile(path):
                return path
    raise SystemExit(
        "No usable TrueType font found. Looked for {} in {}".format(candidates, _FONT_DIRS)
    )


_REGULAR_PATH = _find_font(_REGULAR_CANDIDATES)
_BOLD_PATH = _find_font(_BOLD_CANDIDATES)


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(_BOLD_PATH if bold else _REGULAR_PATH, size)


# --------------------------------------------------------------------------------------
# Drawing helpers
# --------------------------------------------------------------------------------------


def vertical_gradient(size: tuple[int, int], top: tuple[int, int, int], bottom: tuple[int, int, int]):
    """Vertical gradient without a numpy dependency; built one scanline at a time."""
    width, height = size
    image = Image.new("RGB", (width, height), top)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(height - 1, 1)
        row = tuple(int(round(top[i] + (bottom[i] - top[i]) * t)) for i in range(3))
        draw.line([(0, y), (width, y)], fill=row)
    return image


def rounded_panel(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    radius: int,
    fill: tuple[int, int, int],
    outline: tuple[int, int, int] | None = None,
    width: int = 2,
) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def wrap(draw: ImageDraw.ImageDraw, text: str, fnt, max_width: int) -> list[str]:
    """Greedy word wrap against real measured widths, so copy cannot overflow its box."""
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = word if not current else current + " " + word
        if draw.textlength(candidate, font=fnt) <= max_width:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def draw_wrapped(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    fnt,
    fill: tuple[int, int, int],
    max_width: int,
    line_spacing: int,
) -> int:
    x, y = xy
    for line in wrap(draw, text, fnt, max_width):
        draw.text((x, y), line, font=fnt, fill=fill)
        y += fnt.size + line_spacing
    return y


# --------------------------------------------------------------------------------------
# The listing set
#
# Each entry is (slug, headline, subhead, accent, mockup). The copy states only things the app
# actually does. In particular there is no effectiveness percentage and no claim of blocking
# everything: the app blocks at DNS resolution and says so, and a listing that overclaims is both
# a policy problem and the thing that generates refund requests.
# --------------------------------------------------------------------------------------

SCREENS = [
    (
        "01_blocked_before_it_loads",
        "Blocked before the page loads",
        "Gambling domains are stopped at DNS resolution, so the request never leaves the device.",
        BLUE_LIGHT,
        "shield",
    ),
    (
        "02_private_by_design",
        "Private by design, not by policy",
        "No telemetry, no analytics, no account. Blocking happens entirely on your device.",
        GREEN_LIGHT,
        "lock",
    ),
    (
        "03_works_offline",
        "Keeps protecting offline",
        "Rules are cached on device, so protection does not stop when the network does.",
        BLUE_LIGHT,
        "offline",
    ),
    (
        "04_per_app_control",
        "Control app by app",
        "Exempt the apps you trust. Blocklist exceptions and split tunnelling per package.",
        ORANGE,
        "apps",
    ),
    (
        "05_survives_network_changes",
        "Survives network changes",
        "Queries are held and re-sent across Wi-Fi and mobile handoffs instead of being dropped.",
        BLUE_LIGHT,
        "handoff",
    ),
    (
        "06_recovery_measured",
        "Recovery you can measure",
        "Savings are counted from days actually observed as protected, not from a start date.",
        GREEN_LIGHT,
        "recovery",
    ),
    (
        "07_risk_scan",
        "Advisory app risk scan",
        "Flags installed gambling apps offline. Advisory only - it never uninstalls anything.",
        ORANGE,
        "scan",
    ),
    (
        "08_battery_aware",
        "Battery-aware, OEM aware",
        "Detects aggressive battery skins and shows exactly which setting to change.",
        BLUE_LIGHT,
        "battery",
    ),
]


# --------------------------------------------------------------------------------------
# Mockups: small representative UI fragments drawn with the same palette as the app
# --------------------------------------------------------------------------------------


def mockup_shield(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    draw.ellipse([cx - 130, cy - 130, cx + 130, cy + 130], fill=(0x1B, 0x33, 0x5C))
    draw.ellipse([cx - 130, cy - 130, cx + 130, cy + 130], outline=accent, width=6)
    # A shield silhouette: shoulders down to a point.
    top, bottom = cy - 74, cy + 82
    draw.polygon(
        [
            (cx, top),
            (cx + 62, top + 26),
            (cx + 62, top + 62),
            (cx, bottom),
            (cx - 62, top + 62),
            (cx - 62, top + 26),
        ],
        fill=accent,
    )
    draw.line([(cx - 26, cy + 4), (cx - 6, cy + 26), (cx + 32, cy - 22)], fill=INK, width=12, joint="curve")


def mockup_lock(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    draw.arc([cx - 86, cy - 96, cx + 86, cy + 44], start=180, end=360, fill=accent, width=16)
    rounded_panel(draw, (cx - 96, cy - 16, cx + 96, cy + 110), 22, accent)
    draw.ellipse([cx - 16, cy + 22, cx + 16, cy + 54], fill=INK)
    draw.rectangle([cx - 7, cy + 48, cx + 7, cy + 78], fill=INK)


def mockup_offline(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    draw.line([(cx - 120, cy + 60), (cx + 120, cy + 60)], fill=accent, width=8)
    draw.line([(cx, cy + 60), (cx, cy - 40)], fill=accent, width=8)
    # Crossed-out signal: honest about the network being absent rather than implying it works.
    draw.line([(cx - 110, cy - 96), (cx + 30, cy - 16)], fill=accent, width=10)
    draw.line([(cx + 30, cy - 96), (cx - 110, cy - 16)], fill=accent, width=10)
    rounded_panel(draw, (cx - 130, cy + 84, cx + 130, cy + 176), 18, (0x1B, 0x33, 0x5C))
    draw.text((cx - 112, cy + 104), "12,480 rules cached", font=font(28), fill=WHITE)


def mockup_apps(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    labels = [("Banking", True), ("Maps", True), ("Sportsbook", False), ("Wallet", True)]
    y = cy - 150
    for label, exempt in labels:
        rounded_panel(draw, (cx - 250, y, cx + 250, y + 62), 16, (0x1B, 0x33, 0x5C))
        draw.text((cx - 226, y + 16), label, font=font(30), fill=WHITE)
        if exempt:
            rounded_panel(draw, (cx + 96, y + 12, cx + 226, y + 50), 19, (0x22, 0x4A, 0x2C))
            draw.text((cx + 118, y + 15), "exempt", font=font(24), fill=GREEN_LIGHT)
        else:
            rounded_panel(draw, (cx + 96, y + 12, cx + 226, y + 50), 19, (0x5A, 0x1F, 0x1F))
            draw.text((cx + 112, y + 15), "blocked", font=font(24), fill=(0xEF, 0x9A, 0x9A))
        y += 78


def mockup_handoff(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    for i, (label, colour) in enumerate([("Wi-Fi", BLUE_LIGHT), ("handoff", ORANGE), ("Mobile", GREEN_LIGHT)]):
        x = cx - 230 + i * 230
        rounded_panel(draw, (x, cy - 60, x + 180, cy + 60), 20, (0x1B, 0x33, 0x5C), outline=colour, width=4)
        draw.text((x + 26, cy - 18), label, font=font(28), fill=WHITE)
        if i < 2:
            draw.line([(x + 188, cy), (x + 222, cy)], fill=WHITE, width=6)
    draw.text((cx - 214, cy + 92), "query held, then re-sent", font=font(30), fill=accent)


def mockup_recovery(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    rounded_panel(draw, (cx - 250, cy - 130, cx + 250, cy + 130), 24, (0x1B, 0x33, 0x5C))
    draw.text((cx - 214, cy - 108), "Observed as protected", font=font(28), fill=(0x9F, 0xB6, 0xD9))
    draw.text((cx - 214, cy - 64), "23 days", font=font(76, bold=True), fill=WHITE)
    draw.text((cx - 214, cy + 18), "Estimated not gambled", font=font(28), fill=(0x9F, 0xB6, 0xD9))
    draw.text((cx - 214, cy + 56), "$412 saved", font=font(48, bold=True), fill=GREEN_LIGHT)


def mockup_scan(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    rounded_panel(draw, (cx - 250, cy - 110, cx + 250, cy + 130), 24, (0x1B, 0x33, 0x5C))
    draw.text((cx - 214, cy - 88), "Risk scan - offline", font=font(30, bold=True), fill=WHITE)
    rows = [("High", RED), ("Medium", ORANGE), ("Clear", GREEN_LIGHT)]
    y = cy - 34
    for label, colour in rows:
        draw.ellipse([cx - 214, y + 6, cx - 190, y + 30], fill=colour)
        draw.text((cx - 174, y), label, font=font(30), fill=WHITE)
        y += 46
    draw.text((cx - 214, cy + 92), "Advisory only - nothing removed", font=font(24), fill=(0x9F, 0xB6, 0xD9))


def mockup_battery(draw: ImageDraw.ImageDraw, box: tuple[int, int], accent) -> None:
    cx, cy = box
    rounded_panel(draw, (cx - 250, cy - 120, cx + 250, cy + 130), 24, (0x1B, 0x33, 0x5C))
    draw.text((cx - 214, cy - 96), "Battery optimisation", font=font(30, bold=True), fill=WHITE)
    draw.ellipse([cx - 214, cy - 40, cx - 178, cy + 4], fill=ORANGE)
    draw.text((cx - 158, cy - 44), "Not exempt", font=font(32), fill=WHITE)
    draw.text((cx - 214, cy + 26), "Device: MIUI / HyperOS", font=font(26), fill=(0x9F, 0xB6, 0xD9))
    rounded_panel(draw, (cx - 214, cy + 62, cx + 40, cy + 112), 14, accent)
    draw.text((cx - 196, cy + 74), "Open settings", font=font(26), fill=INK)


MOCKUPS = {
    "shield": mockup_shield,
    "lock": mockup_lock,
    "offline": mockup_offline,
    "apps": mockup_apps,
    "handoff": mockup_handoff,
    "recovery": mockup_recovery,
    "scan": mockup_scan,
    "battery": mockup_battery,
}


# --------------------------------------------------------------------------------------
# Composition
# --------------------------------------------------------------------------------------


def render_screenshot(slug: str, headline: str, subhead: str, accent, mockup: str) -> Image.Image:
    image = vertical_gradient((PHONE_W, PHONE_H), INK, INK_2)
    draw = ImageDraw.Draw(image)

    # A soft accent wash behind the header so the type is not sitting on flat black.
    draw.ellipse([-300, -420, 760, 460], fill=INK_3)

    draw.text((96, 150), "GAMBLOCK SHIELD", font=font(34, bold=True), fill=accent)

    headline_font = font(76, bold=True)
    y = draw_wrapped(draw, (96, 214), headline, headline_font, WHITE, PHONE_W - 192, 14)

    draw_wrapped(draw, (96, y + 26), subhead, font(38), (0xC6, 0xD6, 0xEE), PHONE_W - 192, 14)

    # Mockup area.
    rounded_panel(draw, (64, 880, PHONE_W - 64, 1660), 40, (0x0D, 0x1B, 0x33), outline=(0x1E, 0x33, 0x55), width=3)
    MOCKUPS[mockup](draw, (PHONE_W // 2, 1180), accent)

    # Feature strip along the bottom: three verifiable facts rather than marketing adjectives.
    strip_y = 1726
    facts = ["On-device only", "No account", "Open source"]
    x = 96
    for fact in facts:
        width = int(draw.textlength(fact, font=font(30, bold=True))) + 64
        rounded_panel(draw, (x, strip_y, x + width, strip_y + 62), 31, (0x16, 0x2A, 0x4A))
        draw.text((x + 32, strip_y + 15), fact, font=font(30, bold=True), fill=accent)
        x += width + 22

    return image


def render_feature_graphic() -> Image.Image:
    image = vertical_gradient((FEATURE_W, FEATURE_H), INK, INK_2)
    draw = ImageDraw.Draw(image)
    draw.ellipse([-200, -260, 620, 400], fill=INK_3)

    draw.text((64, 92), "GAMBLOCK SHIELD", font=font(30, bold=True), fill=BLUE_LIGHT)
    draw.text((64, 146), "Block gambling at the DNS layer", font=font(58, bold=True), fill=WHITE)
    draw_wrapped(
        draw,
        (64, 232),
        "On-device protection. No telemetry, no account, works offline.",
        font(34),
        (0xC6, 0xD6, 0xEE),
        620,
        10,
    )

    draw_wrapped(
        draw,
        (64, 372),
        "Real filtering, not a timer.",
        font(30, bold=True),
        GREEN_LIGHT,
        620,
        8,
    )

    mockup_shield(draw, (856, 250), BLUE_LIGHT)
    return image


def save_rgb(image: Image.Image, path: str) -> int:
    """Write 24-bit RGB and verify it, because an alpha channel fails Play's upload validation."""
    if image.mode != "RGB":
        image = image.convert("RGB")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, format="PNG", optimize=True)
    with Image.open(path) as check:
        if check.mode != "RGB":
            raise SystemExit("{} was written as {}, Play requires 24-bit RGB".format(path, check.mode))
    return os.path.getsize(path)


def main() -> None:
    total = 0
    for slug, headline, subhead, accent, mockup in SCREENS:
        image = render_screenshot(slug, headline, subhead, accent, mockup)
        path = os.path.join(SCREENSHOT_DIR, slug + ".png")
        size = save_rgb(image, path)
        total += size
        print("  {0:<44} {1}x{2}  {3:>8,} B".format(slug + ".png", PHONE_W, PHONE_H, size))

    feature = render_feature_graphic()
    path = os.path.join(OUT_DIR, "feature_graphic.png")
    size = save_rgb(feature, path)
    total += size
    print("  {0:<44} {1}x{2}  {3:>8,} B".format("feature_graphic.png", FEATURE_W, FEATURE_H, size))
    print("  generated {0} images, {1:,} bytes total".format(len(SCREENS) + 1, total))


if __name__ == "__main__":
    main()