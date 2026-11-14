"""Per-app writing styles.

Every app belongs to a category (messaging, email, code, documents, or none), and every category
has a style:

- verbatim: exactly what you said (only "press enter" and spacing are handled)
- clean:    the normal rule pipeline (fillers, spoken punctuation, capitals)
- casual:   clean, plus no full stop at the end of a one-sentence message
- formal:   clean, never drops the full stop

Rules work without AI. When AI rewrite is enabled, llm_instruction() tells the model how to
rewrite the rule-cleaned text in the same style.
"""
from . import cleanup

STYLES = ("verbatim", "clean", "casual", "formal")
CATEGORIES = ("messaging", "email", "code", "documents")

_INSTRUCTIONS = {
    "verbatim": "Fix only obvious speech-recognition errors; keep every word, the word order "
                "and the speaker's own phrasing.",
    "clean": "Remove filler words, false starts and repetitions, fix grammar and punctuation, "
             "and keep the speaker's meaning and voice.",
    "casual": "Make it read like a natural chat message: relaxed and concise, no greeting or "
              "sign-off, no full stop after a single sentence.",
    "formal": "Make it read like a clear, polite, well-structured professional message with "
              "complete sentences and correct punctuation.",
}
_SAME_LANGUAGE = " Reply in the same language as the transcript."


def category_for(app: str, cfg: dict) -> str | None:
    app = (app or "").lower()
    if not app:
        return None
    cats = cfg.get("styles", {}).get("categories", {})
    for name, cat in cats.items():
        if app in [a.lower() for a in cat.get("apps", [])]:
            return name
    return None


def style_for(app: str, cfg: dict) -> str:
    styles = cfg.get("styles", {})
    app_l = (app or "").lower()
    if not styles.get("enabled", True):
        # Behaviour before styles existed: casual apps drop the final full stop.
        casual = [a.lower() for a in cfg.get("casual_apps", [])]
        return "casual" if app_l in casual else "clean"
    cat = category_for(app_l, cfg)
    if cat is not None:
        style = styles["categories"][cat].get("style", "clean")
    else:
        style = styles.get("default", "clean")
    return style if style in STYLES else "clean"


def apply_rules(text: str, style: str, cleanup_cfg: dict) -> tuple[str, bool]:
    """Rule-based styling. Returns (text, press_enter_requested)."""
    if style == "verbatim":
        press_enter = False
        if cleanup_cfg.get("press_enter_command", True):
            text, press_enter = cleanup.extract_press_enter(text)
        return cleanup.normalize_spacing(text), press_enter
    text, press_enter = cleanup.clean(text, cleanup_cfg, is_casual_app=False)
    if style == "casual" and cleanup_cfg.get("drop_trailing_period_in_casual_apps", True):
        text = cleanup.drop_trailing_period(text)
    return text, press_enter


def llm_instruction(style: str) -> str:
    return _INSTRUCTIONS.get(style, _INSTRUCTIONS["clean"]) + _SAME_LANGUAGE
