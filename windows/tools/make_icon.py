"""Regenerate assets/flowclone.ico (exe + installer icon) from flowclone/icon.py.

Usage (from the windows/ folder):  python tools/make_icon.py
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from flowclone.icon import draw_icon  # noqa: E402

out = ROOT / "assets" / "flowclone.ico"
out.parent.mkdir(exist_ok=True)
draw_icon(256).save(out, sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
print(f"wrote {out}")
