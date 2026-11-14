"""Dictation statistics for the Home page: words per day, streaks, speed, time saved, top apps.
Stored in %APPDATA%/FlowClone/stats.json.

Only dictations recorded by this version carry their duration ("timed"), so speed and time
saved are computed from timed_words / seconds_dictated, never from older totals.
"""
import json
import re
import threading
from copy import deepcopy
from datetime import date, timedelta
from pathlib import Path

from .config import APP_DIR

STATS_PATH = APP_DIR / "stats.json"
KEEP_DAYS = 90

# Scripts written without spaces between words: count each character as a word.
_CJK = re.compile(r"[぀-ヿ㐀-䶿一-鿿가-힯豈-﫿]")


def count_words(text: str) -> int:
    cjk = len(_CJK.findall(text))
    rest = _CJK.sub(" ", text)
    return cjk + len(rest.split())


def _defaults() -> dict:
    return {"total_words": 0, "total_dictations": 0, "days": {}, "timed_words": 0,
            "seconds_dictated": 0.0, "apps": {}, "best_streak": 0}


class Stats:
    def __init__(self, path: Path | None = None, today=date.today):
        self._lock = threading.Lock()
        self.path = path or STATS_PATH
        self._today = today
        self.data = _defaults()
        if self.path.exists():
            try:
                loaded = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    self.data.update(loaded)
            except (json.JSONDecodeError, OSError):
                pass

    # ---------- writing ----------

    def add(self, text: str, seconds: float = 0.0, app: str = ""):
        words = count_words(text)
        today = self._today().isoformat()
        with self._lock:
            d = self.data
            d["total_words"] += words
            d["total_dictations"] += 1
            d["days"][today] = d["days"].get(today, 0) + words
            if seconds > 0:
                d["timed_words"] += words
                d["seconds_dictated"] = round(d["seconds_dictated"] + seconds, 2)
            if app:
                d["apps"][app] = d["apps"].get(app, 0) + words
            cutoff = (self._today() - timedelta(days=KEEP_DAYS)).isoformat()
            d["days"] = {k: v for k, v in d["days"].items() if k >= cutoff}
            d["best_streak"] = max(d["best_streak"], self._streak_unlocked())
            try:
                self.path.write_text(json.dumps(d), encoding="utf-8")
            except OSError:
                pass

    # ---------- reading (all under the lock, returning copies) ----------

    def snapshot(self) -> dict:
        with self._lock:
            return deepcopy(self.data)

    def today(self) -> int:
        with self._lock:
            return self.data["days"].get(self._today().isoformat(), 0)

    def this_week(self) -> int:
        cutoff = (self._today() - timedelta(days=6)).isoformat()
        with self._lock:
            return sum(w for d, w in self.data["days"].items() if d >= cutoff)

    def _streak_unlocked(self) -> int:
        days = self.data["days"]
        day = self._today()
        if days.get(day.isoformat(), 0) <= 0:
            day -= timedelta(days=1)  # nothing yet today: yesterday's streak still counts
        n = 0
        while days.get(day.isoformat(), 0) > 0:
            n += 1
            day -= timedelta(days=1)
        return n

    def streak(self) -> tuple[int, int]:
        """(current, best) in consecutive days with at least one word."""
        with self._lock:
            cur = self._streak_unlocked()
            return cur, max(cur, self.data["best_streak"])

    def wpm(self) -> int | None:
        with self._lock:
            seconds, words = self.data["seconds_dictated"], self.data["timed_words"]
        if seconds < 60:
            return None
        return round(words / (seconds / 60))

    def time_saved_minutes(self, typing_wpm: int = 40) -> int:
        with self._lock:
            seconds, words = self.data["seconds_dictated"], self.data["timed_words"]
        return max(0, round(words / typing_wpm - seconds / 60))

    def top_apps(self, n: int = 5) -> list[tuple[str, int]]:
        with self._lock:
            items = list(self.data["apps"].items())
        return sorted(items, key=lambda kv: kv[1], reverse=True)[:n]

    def days_series(self, n: int = 30) -> list[tuple[str, int]]:
        """Oldest first, one entry per day including days without dictation."""
        today = self._today()
        with self._lock:
            days = dict(self.data["days"])
        out = []
        for i in range(n - 1, -1, -1):
            day = (today - timedelta(days=i)).isoformat()
            out.append((day, days.get(day, 0)))
        return out
