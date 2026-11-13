"""System tray icon with the app menu."""
import logging
import os
import subprocess
import threading

import pystray

from .config import APP_DIR, HISTORY_PATH
from .icon import draw_icon


def _make_icon_image(active: bool = False):
    return draw_icon(64, active)


log = logging.getLogger("flowclone")


def _safe(fn, *args):
    try:
        fn(*args)
    except Exception:
        log.exception("tray action failed")


def _open_history():
    def run():
        HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
        HISTORY_PATH.touch(exist_ok=True)
        subprocess.Popen(["notepad.exe", str(HISTORY_PATH)])
    _safe(run)


class Tray:
    def __init__(self, app):
        self.app = app
        menu = pystray.Menu(
            pystray.MenuItem("Open FlowClone Hub", lambda: app.open_hub(), default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Toggle hands-free dictation", lambda: app.toggle_hands_free()),
            pystray.MenuItem("Paste last transcript", lambda: app.paste_last()),
            pystray.MenuItem("Open history file", lambda: _open_history()),
            pystray.MenuItem("Open settings folder", lambda: _safe(os.startfile, APP_DIR)),
            pystray.MenuItem("Reload dictionary && snippets", lambda: app.lexicon.reload()),
            pystray.MenuItem("Retry speech model download", lambda: app.retry_model_load(),
                             enabled=lambda item: app.model_failed),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit", lambda: app.quit()),
        )
        self.icon = pystray.Icon("FlowClone", _make_icon_image(), "FlowClone", menu)

    def start(self):
        threading.Thread(target=self.icon.run, daemon=True, name="tray").start()

    def set_active(self, active: bool):
        self.icon.icon = _make_icon_image(active)

    def set_tooltip(self, text: str):
        try:
            self.icon.title = text[:127]  # Windows tooltip limit
        except Exception:
            log.debug("tooltip update failed", exc_info=True)

    def refresh_menu(self):
        try:
            self.icon.update_menu()
        except Exception:
            log.debug("menu refresh failed", exc_info=True)

    def stop(self):
        self.icon.stop()
