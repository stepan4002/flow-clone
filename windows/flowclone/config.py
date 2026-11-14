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
        "command_modifier": "shift",           # hold it too (Ctrl+Win+Shift) for Command Mode
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
        "tone_matching": True,                 # rewrite in the style of the app's category
        "provider": "ollama",                  # "ollama" | "openai" (any OpenAI-compatible API) | "anthropic"
        "api_base": "",                        # OpenAI-compatible base URL ("" = api.openai.com/v1)
        "api_model": "",                       # model for the openai provider
        "anthropic_model": "",                 # model for the anthropic provider
    },
    # Per-app writing styles: verbatim | clean | casual | formal (AI rewrite only if llm.enabled).
    "styles": {
        "enabled": True,
        "default": "clean",
        "categories": {
            "messaging": {"apps": [], "style": "casual"},   # filled from casual_apps
            "email": {"apps": ["outlook.exe", "olk.exe", "thunderbird.exe"], "style": "formal"},
            "code": {"apps": ["code.exe", "cursor.exe", "windsurf.exe", "devenv.exe",
                              "pycharm64.exe", "idea64.exe", "windowsterminal.exe"],
                     "style": "verbatim"},
            "documents": {"apps": ["winword.exe", "notion.exe", "obsidian.exe", "onenote.exe"],
                          "style": "clean"},
        },
    },
    "ui": {"theme": "system", "onboarding_done": False, "window": None},
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
        CONFIG_PATH.write_text(json.dumps(defaults(), indent=2), encoding="utf-8")
        log.info("wrote default config to %s", CONFIG_PATH)
        return defaults()
    return migrate(user)


def defaults() -> dict:
    d = json.loads(json.dumps(DEFAULTS))  # deep copy
    d["styles"]["categories"]["messaging"]["apps"] = list(d["casual_apps"])
    return d


def _drop_bad_sections(base: dict, user: dict) -> dict:
    """Ignore hand-edited values whose type doesn't match a section of the defaults (e.g.
    "overlay": null): one bad entry must not stop FlowClone from starting."""
    out = {}
    for k, v in user.items():
        if isinstance(base.get(k), dict):
            if not isinstance(v, dict):
                log.warning("config: ignoring %r (expected a section)", k)
                continue
            v = _drop_bad_sections(base[k], v)
        out[k] = v
    return out


def migrate(user: dict) -> dict:
    """Merge a config written by any earlier version over the defaults."""
    if not isinstance(user, dict):
        user = {}
    user = _drop_bad_sections(DEFAULTS, user)
    merged = _merge(defaults(), user)
    ui = user.get("ui") if isinstance(user.get("ui"), dict) else {}
    if "onboarding_done" not in ui:
        merged["ui"]["onboarding_done"] = True   # an existing user: no first-run tour
    if "styles" not in user:
        # Keep the user's own list of casual apps as the messaging category.
        merged["styles"]["categories"]["messaging"]["apps"] = list(merged.get("casual_apps") or [])
    return merged


def save_config(config: dict):
    """Atomic write: a crash mid-save never leaves a truncated config.json."""
    APP_DIR.mkdir(parents=True, exist_ok=True)
    atomic_write(CONFIG_PATH, json.dumps(config, indent=2, ensure_ascii=False))


def atomic_write(path: Path, text: str):
    tmp = path.with_name(path.name + ".tmp")
    try:
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, path)
    except OSError:
        try:
            tmp.unlink(missing_ok=True)
        except OSError:
            pass
        raise
