"""Personal dictionary, misspelling corrections, text replacements, and snippets.

Files (auto-created with examples in %APPDATA%/FlowClone):
- dictionary.json: {"words": [...], "corrections": {"wrong": "right"}, "replacements": {"c30": "https://..."}}
    words        -> bias the recognizer toward these terms (Whisper engine) and protect casing
    corrections  -> always fix a misrecognized word ("Eric" -> "Erik")
    replacements -> shorthand you SAY -> text that gets inserted
- snippets.json: {"trigger phrase": "full expansion text"}
    say the trigger anywhere in a dictation -> the expansion is inserted in its place
"""
import json
import logging
import re

from .config import DICTIONARY_PATH, SNIPPETS_PATH

log = logging.getLogger("flowclone")

_DEFAULT_DICTIONARY = {
    "words": ["FlowClone", "GitHub"],
    "corrections": {},
    "replacements": {},
}
# Example snippets: edit them in the Hub (or snippets.json) to use your own details.
_DEFAULT_SNIPPETS = {
    "insert my email": "you@example.com",
    "insert my signature": "Best regards,\nYour Name",
}


def _load(path, default):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(default, indent=2, ensure_ascii=False), encoding="utf-8")
        return dict(default)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as e:
        log.warning("failed to read %s: %s", path, e)
        return dict(default)


def _mtime(path) -> float:
    try:
        return path.stat().st_mtime
    except OSError:
        return 0.0


class Lexicon:
    def __init__(self):
        self.reload()

    def reload(self):
        d = _load(DICTIONARY_PATH, _DEFAULT_DICTIONARY)
        self.words: list[str] = list(d.get("words", []))
        self.corrections: dict = dict(d.get("corrections", {}))
        self.replacements: dict = dict(d.get("replacements", {}))
        self.snippets: dict = dict(_load(SNIPPETS_PATH, _DEFAULT_SNIPPETS))
        self._stamp = (_mtime(DICTIONARY_PATH), _mtime(SNIPPETS_PATH))

    def reload_if_changed(self) -> bool:
        """Pick up hand edits of dictionary.json / snippets.json (checked before each dictation)."""
        if (_mtime(DICTIONARY_PATH), _mtime(SNIPPETS_PATH)) != self._stamp:
            self.reload()
            log.info("dictionary/snippets reloaded")
            return True
        return False

    def save_dictionary(self, words: list[str], corrections: dict, replacements: dict):
        DICTIONARY_PATH.parent.mkdir(parents=True, exist_ok=True)
        DICTIONARY_PATH.write_text(json.dumps(
            {"words": words, "corrections": corrections, "replacements": replacements},
            indent=2, ensure_ascii=False), encoding="utf-8")
        self.reload()

    def save_snippets(self, snippets: dict):
        SNIPPETS_PATH.parent.mkdir(parents=True, exist_ok=True)
        SNIPPETS_PATH.write_text(json.dumps(snippets, indent=2, ensure_ascii=False),
                                 encoding="utf-8")
        self.reload()

    @staticmethod
    def _replace_phrase(text: str, phrase: str, replacement: str) -> str:
        pattern = re.compile(r"\b" + re.escape(phrase) + r"\b[.,]?", re.IGNORECASE)
        return pattern.sub(lambda _m: replacement, text)

    def apply(self, text: str) -> str:
        for wrong, right in self.corrections.items():
            text = self._replace_phrase(text, wrong, right)
        for short, expansion in self.replacements.items():
            text = self._replace_phrase(text, short, expansion)
        for trigger, expansion in self.snippets.items():
            text = self._replace_phrase(text, trigger, expansion)
        # Protect dictionary-word casing (e.g. "gitnexus" -> "GitNexus")
        for word in self.words:
            text = self._replace_phrase(text, word, word) if word.lower() in text.lower() else text
        return text
