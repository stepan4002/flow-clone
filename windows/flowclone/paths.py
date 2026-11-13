"""Where things live, for both a source checkout and the frozen FlowClone.exe.

Everything here is a function (not a module constant) so tests can switch between
"frozen" and "source" behaviour, and nothing Windows-only is imported at module level.
"""
import os
import sys
from pathlib import Path

_SOURCE_ROOT = Path(__file__).resolve().parent.parent  # the windows/ folder


def is_frozen() -> bool:
    """True inside the PyInstaller-built FlowClone.exe."""
    return bool(getattr(sys, "frozen", False))


def resource(rel: str) -> Path:
    """A bundled read-only file (icon, notices): next to the code, or in the exe's bundle."""
    base = Path(getattr(sys, "_MEIPASS", _SOURCE_ROOT)) if is_frozen() else _SOURCE_ROOT
    return base / rel


def local_dir() -> Path:
    """Per-user machine-local data (the downloaded speech model)."""
    return Path(os.environ.get("LOCALAPPDATA", Path.home())) / "FlowClone"


def models_dir() -> Path:
    return local_dir() / "models"


def launch_command() -> str:
    """The command that starts this FlowClone (written to the autostart Run key)."""
    if is_frozen():
        return f'"{sys.executable}"'
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    exe = pythonw if pythonw.exists() else Path(sys.executable)
    return f'"{exe}" "{_SOURCE_ROOT / "run.py"}"'
