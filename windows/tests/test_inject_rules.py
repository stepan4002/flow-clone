"""Command Mode selection rules (no keyboard or clipboard needed)."""
import sys
import types

sys.modules.setdefault("keyboard", types.ModuleType("keyboard"))
sys.modules.setdefault("pyperclip", types.SimpleNamespace(PyperclipException=Exception))

from flowclone import inject  # noqa: E402


def test_terminals_never_get_ctrl_c():
    assert not inject.can_read_selection("WindowsTerminal.exe")
    assert inject.can_read_selection("code.exe") and inject.can_read_selection("")


def test_editor_line_copy_is_not_a_selection():
    assert inject._looks_like_line_copy("API_KEY=sk-123\r\n")
    assert inject._looks_like_line_copy("x = 1\n")
    assert not inject._looks_like_line_copy("two\nlines\n")
    assert not inject._looks_like_line_copy("selected words")
