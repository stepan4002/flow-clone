"""Dictation statistics (words/day, totals) — the Hub's Home tab data.
Stored in %APPDATA%/FlowClone/stats.json."""
import json
import threading
from datetime import date, timedelta

from .config import APP_DIR

STATS_PATH = APP_DIR / "stats.json"


class Stats:
    def __init__(self):
        self._lock = threading.Lock()
        self.data = {"total_words": 0, "total_dictations": 0, "days": {}}
        if STATS_PATH.exists():
            try:
                self.data.update(json.loads(STATS_PATH.read_text(encoding="utf-8")))
            except (json.JSONDecodeError, OSError):
                pass

    def add(self, text: str):
        words = len(text.split())
        today = date.today().isoformat()
        with self._lock:
            self.data["total_words"] += words
            self.data["total_dictations"] += 1
            self.data["days"][today] = self.data["days"].get(today, 0) + words
            # keep only the last 90 days
            cutoff = (date.today() - timedelta(days=90)).isoformat()
            self.data["days"] = {d: w for d, w in self.data["days"].items() if d >= cutoff}
            try:
                STATS_PATH.write_text(json.dumps(self.data), encoding="utf-8")
            except OSError:
                pass

    def today(self) -> int:
        return self.data["days"].get(date.today().isoformat(), 0)

    def this_week(self) -> int:
        cutoff = (date.today() - timedelta(days=6)).isoformat()
        return sum(w for d, w in self.data["days"].items() if d >= cutoff)
