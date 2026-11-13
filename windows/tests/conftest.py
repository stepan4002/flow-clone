"""Make the `flowclone` package importable when running `pytest` from windows/ or the repo root."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
