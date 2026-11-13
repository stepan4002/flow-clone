"""System tray icon with the app menu."""
import os
import threading

import pystray
from PIL import Image, ImageDraw

from .config import APP_DIR, HISTORY_PATH


def _make_icon_image(active: bool = False) -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    color = (229, 72, 77, 255) if active else (74, 144, 217, 255)
    d.ellipse((8, 8, 56, 56), fill=(28, 28, 30, 255))
    d.rounded_rectangle((26, 16, 38, 38), radius=6, fill=color)   # mic body
    d.arc((20, 26, 44, 46), start=0, end=180, fill=color, width=3)
    d.line((32, 46, 32, 52), fill=color, width=3)
    return img


class Tray:
    def __init__(self, app):
        self.app = app
        menu = pystray.Menu(
            pystray.MenuItem("Open FlowClone Hub", lambda: app.open_hub(), default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Toggle hands-free dictation", lambda: app.toggle_hands_free()),
            pystray.MenuItem("Paste last transcript", lambda: app.paste_last()),
            pystray.MenuItem("Open history file", lambda: os.startfile(HISTORY_PATH)),
            pystray.MenuItem("Open settings folder", lambda: os.startfile(APP_DIR)),
            pystray.MenuItem("Reload dictionary && snippets", lambda: app.lexicon.reload()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit", lambda: app.quit()),
        )
        self.icon = pystray.Icon("FlowClone", _make_icon_image(), "FlowClone", menu)

    def start(self):
        threading.Thread(target=self.icon.run, daemon=True, name="tray").start()

    def set_active(self, active: bool):
        self.icon.icon = _make_icon_image(active)

    def stop(self):
        self.icon.stop()
