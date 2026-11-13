"""Tests for the rule-based transcript cleanup (flowclone/cleanup.py)."""
import pytest

from flowclone import cleanup

DEFAULTS: dict = {}  # empty settings -> every rule uses its default (enabled)


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("um so I think uh we should go", "So I think we should go"),
        ("hello comma how are you question mark", "Hello, how are you?"),
        ("send it tomorrow scratch that send it today", "Send it today"),
        ("bring an umbrella", "Bring an umbrella"),            # "um" inside a word is kept
        ("look at the periodic table", "Look at the periodic table"),  # "period" inside a word is kept
        ("first line new line second line", "First line\nsecond line"),
        ("it works exclamation mark", "It works!"),
        ("done period next one", "Done. Next one"),
    ],
)
def test_clean_pipeline(raw, expected):
    text, press_enter = cleanup.clean(raw, DEFAULTS)
    assert text == expected
    assert press_enter is False


def test_press_enter_is_extracted_and_flagged():
    text, press_enter = cleanup.clean("see you soon press enter", DEFAULTS)
    assert text == "See you soon"
    assert press_enter is True


def test_press_enter_can_be_disabled():
    text, press_enter = cleanup.clean("see you soon press enter", {"press_enter_command": False})
    assert press_enter is False
    assert text.lower().endswith("press enter")


def test_fillers_kept_when_disabled():
    text, _ = cleanup.clean("um okay", {"remove_fillers": False})
    assert text == "Um okay"


def test_casual_app_drops_single_trailing_period():
    text, _ = cleanup.clean("sounds good.", DEFAULTS, is_casual_app=True)
    assert text == "Sounds good"


def test_non_casual_app_keeps_trailing_period():
    text, _ = cleanup.clean("sounds good.", DEFAULTS, is_casual_app=False)
    assert text == "Sounds good."


def test_multi_sentence_keeps_periods_in_casual_app():
    assert cleanup.drop_trailing_period("One. Two.") == "One. Two."


def test_normalize_spacing():
    assert cleanup.normalize_spacing("hello   ,world  .") == "hello, world."
