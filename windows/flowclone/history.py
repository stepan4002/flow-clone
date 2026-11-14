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


def _str(v) -> str:
    return v if isinstance(v, str) else ""


def _normalise(entry: dict) -> dict:
    """Fill in what older versions didn't write and repair hand-edited values."""
    for key in ("ts", "app", "raw", "text"):
        entry[key] = _str(entry.get(key))
    if not isinstance(entry.get("id"), str) or not entry["id"]:
        entry["id"] = _legacy_id(entry)
    if not isinstance(entry.get("duration_s"), (int, float)):
        entry["duration_s"] = 0.0
    if not isinstance(entry.get("style"), (str, type(None))):
        entry["style"] = None
    if not isinstance(entry.get("words"), int):
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
        from .config import atomic_write
        try:
            limit = int(self.limit) if self.limit else 1000
            lines = self.path.read_text(encoding="utf-8").splitlines()
            if len(lines) > limit * 2:
                atomic_write(self.path, "\n".join(lines[-limit:]) + "\n")
        except (OSError, ValueError, TypeError):
            pass

    def delete(self, entry_id: str) -> bool:
        with self._lock:
            entries = self._read_all()
            kept = [e for e in entries if e["id"] != entry_id]
            if len(kept) == len(entries):
                return False
            return self._write_all(kept)

    def clear(self) -> bool:
        with self._lock:
            return self._write_all([])

    # ---------- reading ----------

    def _read_all(self) -> list[dict]:
        """Oldest first. Caller holds the lock."""
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return []
        out, seen = [], set()
        for line in lines:
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(entry, dict):
                entry = _normalise(entry)
                base, n = entry["id"], 1
                while entry["id"] in seen:  # identical old lines: give each its own id
                    entry["id"] = f"{base}-{n}"
                    n += 1
                seen.add(entry["id"])
                out.append(entry)
        return out

    def _write_all(self, entries: list[dict]) -> bool:
        from .config import atomic_write
        text = "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in entries)
        try:
            atomic_write(self.path, text)
            return True
        except OSError as e:
            import logging
            logging.getLogger("flowclone").warning("could not write the history: %s", e)
            return False

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
