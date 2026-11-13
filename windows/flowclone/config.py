"""Configuration: loaded from %APPDATA%/FlowClone/config.json, merged over defaults."""
import json
import os
import logging
from pathlib import Path

log = logging.getLogger("flowclone")

APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "FlowClone"
CONFIG_PATH = APP_DIR / "config.json"
DICTIONARY_PATH = APP_DIR / "dictionary.json"
SNIPPETS_PATH = APP_DIR / "snippets.json"
HISTORY_PATH = APP_DIR / "history.jsonl"

DEFAULTS = {
    "engine": "parakeet",          # "parakeet" (fast, EN + 25 EU languages) or "whisper" (90+ languages)
    "whisper_model": "small",      # tiny/base/small/medium/large-v3-turbo (engine="whisper" only)
    "language": None,              # None = auto-detect (whisper only; parakeet detects internally)
    "hotkeys": {
        "push_to_talk": ["ctrl", "windows"],   # hold all keys to record, release to insert
        "paste_last": "shift+alt+z",
        "copy_last": "shift+alt+x",
    },
    "double_tap_hands_free": True,             # double-tap push-to-talk = hands-free toggle
    "hands_free_max_minutes": 20,
    "min_recording_seconds": 0.4,              # shorter recordings are treated as taps and discarded
    "audio": {"device": None, "sample_rate": 16000},
    "cleanup": {
        "remove_fillers": True,
        "spoken_punctuation": True,
        "scratch_that": True,
        "capitalize": True,
        "drop_trailing_period_in_casual_apps": True,
        "press_enter_command": True,           # say "press enter" at the end to auto-send
    },
    "casual_apps": [
        "slack.exe", "discord.exe", "whatsapp.exe", "telegram.exe",
        "ms-teams.exe", "teams.exe", "signal.exe",
    ],
    "llm": {                                   # optional AI polish via local Ollama
        "enabled": False,
        "url": "http://localhost:11434",
        "model": "qwen3:1.7b",
        "timeout_seconds": 12,
        "tone_matching": True,                 # tell the LLM which app you're dictating into
    },
    "injection": {
        "mode": "paste",                       # "paste" (clipboard+Ctrl+V) or "type" (per-char, for paste-blocking apps)
        "restore_clipboard_delay_ms": 300,
        "type_mode_apps": [],                  # exe names that should always use type mode, e.g. ["mstsc.exe"]
    },
    "overlay": {"enabled": True},
    "sounds": True,                            # audio cues on record start/stop
    "history_limit": 1000,
}


def _merge(base: dict, override: dict) -> dict:
    out = dict(base)
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            out[k] = _merge(base[k], v)
        else:
            out[k] = v
    return out


def load_config() -> dict:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    if CONFIG_PATH.exists():
        try:
            user = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as e:
            log.warning("failed to read %s: %s — using defaults", CONFIG_PATH, e)
            user = {}
    else:
        user = {}
        CONFIG_PATH.write_text(json.dumps(DEFAULTS, indent=2), encoding="utf-8")
        log.info("wrote default config to %s", CONFIG_PATH)
    return _merge(DEFAULTS, user)


def save_config(config: dict):
    APP_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(config, indent=2, ensure_ascii=False),
                           encoding="utf-8")
