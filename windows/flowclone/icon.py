"""The FlowClone icon files (drawn by tools/make_icon.py, shipped in assets/)."""
from pathlib import Path

from . import paths


def icon_path(active: bool = False) -> Path:
    """The .ico for the tray / windows; the red one while recording."""
    return paths.resource("assets/flowclone-active.ico" if active else "assets/flowclone.ico")
