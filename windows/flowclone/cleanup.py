"""Rule-based transcript cleanup — the free, 0 ms tier of Wispr Flow's "Polish" layer.

Handles: filler removal, spoken punctuation commands, "scratch that" backtracking,
capitalization, spacing, the "press enter" voice command, and casual-app trailing
period dropping. The optional LLM tier (llm.py) handles restatement-style
self-corrections and tone matching on top of this.
"""
import re

# Standalone filler words in EN / CS / FR / ES (kept conservative on purpose:
# words like "like" or Spanish "este" are real words too often to strip safely).
_FILLERS = re.compile(
    r"(?:(?<=^)|(?<=[\s,.;!?]))(?:um+|uh+|uhm+|erm+|ehm+|hmm+|mmm+|euh+)(?:[,.]?)(?=$|[\s,.;!?])",
    re.IGNORECASE,
)

_SCRATCH = re.compile(r"\b(?:scratch that|delete that|forget that)\b[,.]?\s*", re.IGNORECASE)

_PRESS_ENTER = re.compile(r"[\s,.!?]*\b(?:press|hit)\s+enter\b[\s,.!?]*$", re.IGNORECASE)

# Spoken punctuation commands (English). Multi-word phrases must come first.
_PUNCT_COMMANDS = [
    (r"new paragraph", "\n\n"),
    (r"new line", "\n"),
    (r"full stop", "."),
    (r"period", "."),
    (r"comma", ","),
    (r"question mark", "?"),
    (r"exclamation (?:mark|point)", "!"),
    (r"semicolon", ";"),
    (r"colon", ":"),
    (r"em dash", "—"),
    (r"open quote", '"'),
    (r"close quote", '"'),
    (r"ellipsis", "..."),
]
_PUNCT_RES = [
    (re.compile(r"\s*,?\s*\b" + phrase + r"\b[.,]?", re.IGNORECASE), mark)
    for phrase, mark in _PUNCT_COMMANDS
]


def remove_fillers(text: str) -> str:
    return _FILLERS.sub("", text)


def apply_scratch_that(text: str) -> str:
    """'blah blah scratch that the real thing' -> 'the real thing'"""
    parts = _SCRATCH.split(text)
    return parts[-1] if len(parts) > 1 else text


def extract_press_enter(text: str) -> tuple[str, bool]:
    m = _PRESS_ENTER.search(text)
    if m:
        return text[: m.start()].rstrip(), True
    return text, False


def apply_spoken_punctuation(text: str) -> str:
    for regex, mark in _PUNCT_RES:
        if mark in ("\n", "\n\n"):
            text = regex.sub(mark, text)
        else:
            text = regex.sub(mark, text)
    return text


def normalize_spacing(text: str) -> str:
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r" +([.,;:!?])", r"\1", text)          # no space before punctuation
    text = re.sub(r"([.,;:!?])(?=[^\s.,;:!?\"')\]])", r"\1 ", text)  # space after punctuation
    text = re.sub(r"[ \t]*\n[ \t]*", "\n", text)
    text = re.sub(r"\.{4,}", "...", text)
    return text.strip()


def capitalize_sentences(text: str) -> str:
    def cap(m):
        return m.group(1) + m.group(2).upper()

    text = re.sub(r"^(\W*)(\w)", cap, text)
    text = re.sub(r"([.!?]\s+|\n\n)(\w)", lambda m: m.group(1) + m.group(2).upper(), text)
    return text


def drop_trailing_period(text: str) -> str:
    """Casual-app tone: 'sounds good.' -> 'sounds good' (single sentence only)."""
    if text.endswith(".") and not text.endswith("..") and text.count(".") == 1 and "\n" not in text:
        return text[:-1]
    return text


def clean(text: str, settings: dict, is_casual_app: bool = False) -> tuple[str, bool]:
    """Full rule pipeline. Returns (cleaned_text, press_enter_requested)."""
    press_enter = False
    if settings.get("scratch_that", True):
        text = apply_scratch_that(text)
    if settings.get("press_enter_command", True):
        text, press_enter = extract_press_enter(text)
    if settings.get("remove_fillers", True):
        text = remove_fillers(text)
    if settings.get("spoken_punctuation", True):
        text = apply_spoken_punctuation(text)
    text = normalize_spacing(text)
    if settings.get("capitalize", True):
        text = capitalize_sentences(text)
    if is_casual_app and settings.get("drop_trailing_period_in_casual_apps", True):
        text = drop_trailing_period(text)
    return text, press_enter
