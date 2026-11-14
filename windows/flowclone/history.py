"""Dictation history: JSONL file in %APPDATA%/FlowClone/history.jsonl.

One JSON object per line: {id, ts, app, raw, text, words, duration_s, style}. Lines written by
older versions only have {ts, app, raw, text}; they get a stable id derived from their content.
All access goes through one lock: the dictation worker appends while the app window reads.
"""
import hashlib
import json
import threading
import time
import uuid
from pathlib import Path

from .config import HISTORY_PATH


def _legacy_id(entry: dict) -> str:
    key = f"{entry.get('ts', '')}\x00{entry.get('raw', '')}\x00{entry.get('text', '')}"
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:12]


def _normalise(entry: dict) -> dict:
    entry.setdefault("id", _legacy_id(entry))
    entry.setdefault("app", "")
    entry.setdefault("raw", "")
    entry.setdefault("text", "")
    entry.setdefault("duration_s", 0.0)
    entry.setdefault("style", None)
    if "words" not in entry:
        from .stats import count_words
        entry["words"] = count_words(entry["text"])
    return entry


class History:
    def __init__(self, limit: int = 1000, path: Path | None = None):
        self.limit = limit
        self.path = path or HISTORY_PATH
        self._lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)

    # ---------- writing ----------

    def append(self, text: str, raw: str, app: str, duration_s: float = 0.0,
               style: str | None = None) -> dict:
        from .stats import count_words
        entry = {"id": uuid.uuid4().hex[:12], "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
                 "app": app, "raw": raw, "text": text, "words": count_words(text),
                 "duration_s": round(float(duration_s), 2), "style": style}
        with self._lock:
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            self._trim()
        return entry

    def _trim(self):
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
            if len(lines) > self.limit * 2:
                self.path.write_text("\n".join(lines[-self.limit:]) + "\n", encoding="utf-8")
        except OSError:
            pass

    def delete(self, entry_id: str) -> bool:
        with self._lock:
            entries = self._read_all()
            kept = [e for e in entries if e["id"] != entry_id]
            if len(kept) == len(entries):
                return False
            self._write_all(kept)
            return True

    def clear(self):
        with self._lock:
            self._write_all([])

    # ---------- reading ----------

    def _read_all(self) -> list[dict]:
        """Oldest first. Caller holds the lock."""
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return []
        out = []
        for line in lines:
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(entry, dict):
                out.append(_normalise(entry))
        return out

    def _write_all(self, entries: list[dict]):
        text = "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in entries)
        tmp = self.path.with_suffix(".jsonl.tmp")
        tmp.write_text(text, encoding="utf-8")
        tmp.replace(self.path)

    def entries(self, limit: int | None = None) -> list[dict]:
        """Newest first."""
        with self._lock:
            entries = self._read_all()
        entries.reverse()
        return entries[:limit] if limit else entries

    def search(self, query: str = "", app: str | None = None, limit: int = 200) -> list[dict]:
        q = query.strip().lower()
        out = []
        for e in self.entries():
            if app and e["app"] != app:
                continue
            if q and q not in e["text"].lower() and q not in e["raw"].lower():
                continue
            out.append(e)
            if len(out) >= limit:
                break
        return out

    def apps(self) -> list[str]:
        return sorted({e["app"] for e in self.entries() if e["app"]})

    def last(self) -> str | None:
        entries = self.entries(limit=1)
        return entries[0]["text"] if entries else None
