"""Dictation history: JSONL file in %APPDATA%/FlowClone/history.jsonl."""
import json
import time

from .config import HISTORY_PATH


class History:
    def __init__(self, limit: int = 1000):
        self.limit = limit
        HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)

    def append(self, text: str, raw: str, app: str):
        entry = {"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "app": app,
                 "raw": raw, "text": text}
        with HISTORY_PATH.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        self._trim()

    def _trim(self):
        try:
            lines = HISTORY_PATH.read_text(encoding="utf-8").splitlines()
            if len(lines) > self.limit * 2:
                HISTORY_PATH.write_text(
                    "\n".join(lines[-self.limit:]) + "\n", encoding="utf-8")
        except OSError:
            pass

    def last(self) -> str | None:
        try:
            lines = HISTORY_PATH.read_text(encoding="utf-8").splitlines()
            if lines:
                return json.loads(lines[-1]).get("text")
        except (OSError, json.JSONDecodeError):
            pass
        return None
