"""Config: defaults, migration of older files, shared with the script install."""
import json

from flowclone import config


def _use_tmp(monkeypatch, tmp_path):
    monkeypatch.setattr(config, "APP_DIR", tmp_path)
    monkeypatch.setattr(config, "CONFIG_PATH", tmp_path / "config.json")


def test_first_run_writes_defaults_and_shows_onboarding(monkeypatch, tmp_path):
    _use_tmp(monkeypatch, tmp_path)
    c = config.load_config()
    assert c["ui"]["onboarding_done"] is False
    assert "slack.exe" in c["styles"]["categories"]["messaging"]["apps"]
    # Quitting mid-onboarding: the key is in the file, so it stays False.
    assert config.load_config()["ui"]["onboarding_done"] is False


def test_existing_user_skips_onboarding_and_keeps_casual_apps(monkeypatch, tmp_path):
    _use_tmp(monkeypatch, tmp_path)
    (tmp_path / "config.json").write_text(json.dumps({
        "casual_apps": ["myslack.exe"], "llm": {"enabled": True}, "future_key": 1}),
        encoding="utf-8")
    c = config.load_config()
    assert c["ui"]["onboarding_done"] is True
    assert c["styles"]["categories"]["messaging"]["apps"] == ["myslack.exe"]
    assert c["llm"]["enabled"] is True and c["llm"]["provider"] == "ollama"
    assert c["future_key"] == 1                      # unknown keys survive (other install)


def test_existing_key_types_unchanged():
    d = config.DEFAULTS
    assert isinstance(d["llm"]["enabled"], bool) and isinstance(d["llm"]["url"], str)
    assert isinstance(d["hotkeys"]["push_to_talk"], list)
    assert isinstance(d["casual_apps"], list) and isinstance(d["overlay"], dict)
