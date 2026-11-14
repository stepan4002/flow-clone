"""Per-app writing styles."""
from flowclone import config, styles

CLEAN = {k: True for k in ("remove_fillers", "spoken_punctuation", "scratch_that", "capitalize",
                           "drop_trailing_period_in_casual_apps", "press_enter_command")}


def test_style_for_categories():
    cfg = config.defaults()
    assert styles.style_for("Slack.exe", cfg) == "casual"
    assert styles.style_for("outlook.exe", cfg) == "formal"
    assert styles.style_for("code.exe", cfg) == "verbatim"
    assert styles.style_for("notepad.exe", cfg) == "clean"
    assert styles.style_for("", cfg) == "clean"
    cfg["styles"]["enabled"] = False
    assert styles.style_for("slack.exe", cfg) == "casual"     # old casual_apps behaviour
    assert styles.style_for("outlook.exe", cfg) == "clean"


def test_apply_rules():
    raw = "um so can we meet tomorrow period"
    assert styles.apply_rules(raw, "clean", CLEAN) == ("So can we meet tomorrow.", False)
    assert styles.apply_rules(raw, "casual", CLEAN) == ("So can we meet tomorrow", False)
    assert styles.apply_rules(raw, "formal", CLEAN) == ("So can we meet tomorrow.", False)
    off = dict(CLEAN, drop_trailing_period_in_casual_apps=False)
    assert styles.apply_rules(raw, "casual", off)[0].endswith(".")


def test_verbatim_keeps_words_but_handles_press_enter():
    text, enter = styles.apply_rules("um  git commit  press enter", "verbatim", CLEAN)
    assert (text, enter) == ("um git commit", True)


def test_instructions_keep_language():
    for s in styles.STYLES:
        assert "same language" in styles.llm_instruction(s)
