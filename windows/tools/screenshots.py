"""Render every page of the FlowClone window with demo data (dev tool).

Usage (from windows/):  python tools/screenshots.py OUT_DIR [dark|light|both]
Uses a temporary profile: your real settings and history are never read or written.
"""
import os
import random
import sys
import tempfile
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
_tmp = tempfile.mkdtemp(prefix="flowclone-shots-")
os.environ["APPDATA"] = os.path.join(_tmp, "roaming")
os.environ["LOCALAPPDATA"] = os.path.join(_tmp, "local")

from PySide6.QtWidgets import QApplication  # noqa: E402

from flowclone import config as cfg  # noqa: E402
from flowclone.history import History  # noqa: E402
from flowclone.lexicon import Lexicon  # noqa: E402
from flowclone.llm import Polisher  # noqa: E402
from flowclone.stats import Stats  # noqa: E402
from flowclone.ui.bridge import Bridge  # noqa: E402
from flowclone.ui.main_window import NAV, MainWindow  # noqa: E402
from flowclone.ui.theme import Theme  # noqa: E402

DEMO = [
    ("slack.exe", "sounds good, I'll push the fix after lunch"),
    ("outlook.exe", "Hi Maria, thanks for the quick reply. Thursday at 3 works for me. "
                    "I'll send the agenda tomorrow morning."),
    ("code.exe", "refactor the download loop so it resumes from the part file"),
    ("chrome.exe", "best open source voice dictation for windows"),
    ("notion.exe", "Meeting notes: launch moved to next week, Anna owns the release checklist."),
    ("slack.exe", "can you review my PR when you get a sec"),
    ("winword.exe", "The results show a clear improvement in recognition accuracy across all "
                    "five languages we tested."),
]


class DemoApp:
    def __init__(self, qt, theme_mode):
        self.config = cfg.load_config()
        self.config["ui"]["theme"] = theme_mode
        self.bridge = Bridge()
        self.theme = Theme(qt, theme_mode)
        self.history = History()
        self.stats = Stats()
        self.lexicon = Lexicon()
        self.lexicon.save_dictionary(["FlowClone", "GitHub", "Kubernetes", "Parakeet", "Siobhán"],
                                     {"Eric": "Erik", "get hub": "GitHub"},
                                     {"my zoom link": "https://zoom.example/j/123"})
        self.polisher = Polisher(self.config["llm"])
        self.model_status = ("ready", 100)
        self.model_failed = False
        self.window = None
        rnd = random.Random(4)
        today = date.today()
        for i in range(29, -1, -1):
            if i in (12, 13, 20):
                continue
            day = today - timedelta(days=i)
            self.stats._today = lambda d=day: d
            for _ in range(rnd.randint(1, 5)):
                app, text = rnd.choice(DEMO)
                self.stats.add(text, seconds=len(text.split()) / 2.4, app=app)
        self.stats._today = date.today
        for app, text in DEMO:
            self.history.append(text, text.lower(), app, duration_s=len(text.split()) / 2.4,
                                style="casual" if app == "slack.exe" else "clean")

    def save_settings(self, mut, live=True):
        mut(self.config)

    def suspend_hotkeys(self): pass
    def resume_hotkeys(self): pass
    def rebuild_polisher(self): pass
    def retry_model_load(self): pass
    def restart(self): pass
    def flash(self, *_): pass
    def paste_into_last_app(self, *_): pass
    def open_hub(self): pass


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "shots")
    modes = {"both": ["dark", "light"]}.get(sys.argv[2] if len(sys.argv) > 2 else "dark",
                                            [sys.argv[2] if len(sys.argv) > 2 else "dark"])
    out.mkdir(parents=True, exist_ok=True)
    qt = QApplication([])
    for mode in modes:
        app = DemoApp(qt, mode)
        win = MainWindow(app)
        app.window = win
        win.resize(1080, 720)
        win.show()
        for key, _, _ in NAV:
            win.show_page(key)
            for _ in range(5):
                qt.processEvents()
            win.grab().save(str(out / f"{key}-{mode}.png"))
        win.hide()
    print(f"saved to {out}")


if __name__ == "__main__":
    main()
