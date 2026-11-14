"""Regenerate the app icons (dev tool, needs Pillow):

  assets/flowclone.ico         exe, installer, tray (idle)
  assets/flowclone-active.ico  tray while recording
  assets/flowclone.png         256 px, for docs

Usage (from the windows/ folder):  python tools/make_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SS = 4  # supersampling for smooth edges

IDLE = ((76, 111, 255), (123, 92, 255))     # accent -> violet
ACTIVE = ((229, 72, 77), (255, 122, 89))    # recording red -> orange


def _gradient(size: int, top, bottom) -> Image.Image:
    g = Image.new("RGBA", (size, size))
    d = ImageDraw.Draw(g)
    for y in range(size):
        t = y / max(1, size - 1)
        c = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,)
        d.line((0, y, size, y), fill=c)
    return g


def draw_icon(size: int = 256, active: bool = False) -> Image.Image:
    big = size * SS
    s = big / 64
    top, bottom = ACTIVE if active else IDLE
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((2 * s, 2 * s, 62 * s, 62 * s), radius=15 * s, fill=255)
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    img.paste(_gradient(big, top, bottom), (0, 0), mask)
    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)
    w = round(4.2 * s)
    d.rounded_rectangle((25 * s, 13 * s, 39 * s, 37 * s), radius=7 * s, fill=white)   # mic body
    d.arc((18 * s, 22 * s, 46 * s, 46 * s), start=0, end=180, fill=white, width=w)    # cradle
    d.line((32 * s, 46 * s, 32 * s, 52 * s), fill=white, width=w)                      # stem
    d.rounded_rectangle((25 * s, 50 * s, 39 * s, 50 * s + w), radius=w / 2, fill=white)  # base
    return img.resize((size, size), Image.LANCZOS)


def main():
    out = ROOT / "assets"
    out.mkdir(exist_ok=True)
    sizes = [(n, n) for n in (16, 20, 24, 32, 40, 48, 64, 128, 256)]
    draw_icon(256).save(out / "flowclone.ico", sizes=sizes)
    draw_icon(256, active=True).save(out / "flowclone-active.ico", sizes=sizes)
    draw_icon(256).save(out / "flowclone.png")
    print(f"wrote icons to {out}")


if __name__ == "__main__":
    main()
