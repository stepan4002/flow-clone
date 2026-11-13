"""Frozen-vs-source paths, logging without a console, engine fallback, version (no Windows APIs)."""
import logging
import re
import sys
import types
from pathlib import Path

import pytest

import flowclone
from flowclone import paths

REPO = Path(__file__).resolve().parents[2]


def test_launch_command_from_source(monkeypatch):
    monkeypatch.delattr(sys, "frozen", raising=False)
    cmd = paths.launch_command()
    assert cmd.endswith('run.py"') and cmd.startswith('"')


def test_launch_command_frozen(monkeypatch):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", r"C:\Apps\FlowClone\FlowClone.exe")
    assert paths.launch_command() == r'"C:\Apps\FlowClone\FlowClone.exe"'


def test_resource_uses_bundle_when_frozen(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)
    assert paths.resource("assets/flowclone.ico") == tmp_path / "assets" / "flowclone.ico"


def test_resource_from_source(monkeypatch):
    monkeypatch.delattr(sys, "frozen", raising=False)
    assert paths.resource("assets/flowclone.ico").is_file()


def test_local_dir_without_localappdata(monkeypatch):
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    assert paths.local_dir() == Path.home() / "FlowClone"
    assert paths.models_dir() == Path.home() / "FlowClone" / "models"


def test_logging_without_console(monkeypatch, tmp_path):
    from flowclone import logging_setup
    monkeypatch.setattr(sys, "stderr", None)
    assert not logging_setup._has_console()


def test_whisper_missing_falls_back_to_parakeet(monkeypatch):
    from flowclone import stt
    monkeypatch.setitem(sys.modules, "faster_whisper", None)  # import fails
    created = []

    class FakeParakeet:
        def __init__(self, progress_cb=None, cancel_event=None):
            created.append(True)

    monkeypatch.setattr(stt, "ParakeetEngine", FakeParakeet)
    notes = []
    stt.create_engine({"engine": "whisper"}, on_fallback=notes.append)
    assert created and notes and "faster-whisper" in notes[0]


def test_real_whisper_errors_are_not_swallowed(monkeypatch):
    from flowclone import stt

    class Broken:
        def __init__(self, *a, **k):
            raise RuntimeError("CUDA driver missing")

    monkeypatch.setitem(sys.modules, "faster_whisper", types.SimpleNamespace(WhisperModel=Broken))
    monkeypatch.setattr(stt, "ParakeetEngine", lambda *a, **k: pytest.fail("silently swapped"))
    with pytest.raises(RuntimeError, match="CUDA"):
        stt.create_engine({"engine": "whisper"})


def test_version_matches_changelog():
    changelog = (REPO / "CHANGELOG.md").read_text(encoding="utf-8")
    newest = re.search(r"^## \[(\d+\.\d+\.\d+)\]", changelog, re.M).group(1)
    assert flowclone.__version__ == newest


def test_build_requirements_cover_runtime_requirements():
    def names(path):
        out = set()
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                out.add(re.split(r"[\[=<>~! ]", line, 1)[0].lower().replace("_", "-"))
        return out

    runtime = names(REPO / "windows" / "requirements.txt") - {"faster-whisper"}
    build = names(REPO / "windows" / "requirements-build.txt")
    assert runtime <= build, f"missing from requirements-build.txt: {runtime - build}"
