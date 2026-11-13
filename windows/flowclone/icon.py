"""The FlowClone microphone icon, drawn in code (tray icon, exe/installer .ico)."""
from PIL import Image, ImageDraw

_BLUE = (74, 144, 217, 255)
_RED = (229, 72, 77, 255)
_BG = (28, 28, 30, 255)


def draw_icon(size: int = 64, active: bool = False) -> Image.Image:
    """The mic icon at `size` px; red while recording (`active`). Designed on a 64 px grid."""
    s = size / 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    color = _RED if active else _BLUE
    width = max(1, round(3 * s))
    d.ellipse((8 * s, 8 * s, 56 * s, 56 * s), fill=_BG)
    d.rounded_rectangle((26 * s, 16 * s, 38 * s, 38 * s), radius=6 * s, fill=color)  # mic body
    d.arc((20 * s, 26 * s, 44 * s, 46 * s), start=0, end=180, fill=color, width=width)
    d.line((32 * s, 46 * s, 32 * s, 52 * s), fill=color, width=width)
    return img
