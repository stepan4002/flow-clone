"""Write THIRD-PARTY-NOTICES.txt for FlowClone.exe from the build environment.

Lists every bundled Python distribution with its version, licence and licence text, plus
the non-Python components (Python itself, Qt 6, PortAudio) and the speech model.

Usage (from windows/, in the build venv):  python tools/gen_notices.py
"""
import importlib.metadata as md
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "THIRD-PARTY-NOTICES.txt"

# Build-only tools: not shipped inside FlowClone.exe.
BUILD_ONLY = {"pyinstaller", "pyinstaller-hooks-contrib", "altgraph", "pefile", "pip",
              "setuptools", "pytest", "iniconfig", "pluggy", "pygments", "pywin32-ctypes"}
# Excluded from the exe by FlowClone.spec.
EXCLUDED = {"faster-whisper", "ctranslate2", "av", "tokenizers", "hf-xet",
            "pystray", "pillow"}  # pystray/Pillow: dev-only since the Qt interface

FIXED = """\
================================================================================
Python {py}
Python Software Foundation License Version 2 — https://docs.python.org/3/license.html

Qt 6.12 and Qt for Python (PySide6 / Shiboken6) 6.12 — the user interface
GNU Lesser General Public License v3 (LGPL-3.0-only); full texts in licenses\LGPL-3.0.txt
and licenses\GPL-3.0.txt next to this file.
FlowClone uses Qt as unmodified, dynamically linked libraries: the Qt DLLs (_internal\PySide6\)
and the PySide6 / Shiboken6 Python files (_internal\PySide6\, _internal\shiboken6\) are
separate files that you may replace with your own builds of the same versions.
Source code: https://download.qt.io/official_releases/qt/6.12/ and
https://code.qt.io/cgit/pyside/pyside-setup.git/ (tag v6.12.0). Copyright (C) The Qt Company Ltd.
and other contributors.
Qt includes third-party code, each under its own permissive licence, listed at
https://doc.qt.io/qt-6/licenses-used-in-qt.html — among them: zlib (zlib licence), libpng
(libpng licence), FreeType (FreeType Project licence: "Portions of this software are copyright
(C) The FreeType Project (www.freetype.org). All rights reserved."), HarfBuzz (MIT), PCRE2
(BSD-3-Clause), double-conversion (BSD-3-Clause), md4c (MIT), libjpeg (IJG), and the Unicode
Character Database (Unicode licence).

PortAudio (bundled with sounddevice)
MIT License — Copyright (c) 1999-2006 Ross Bencina and Phil Burk

Speech model (downloaded on first start, not part of the installer)
NVIDIA Parakeet-TDT-0.6B-v3 — Creative Commons Attribution 4.0 (CC-BY-4.0)
https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
ONNX conversion by Ilya Stupakov: https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx

FlowClone's own line icons (assets\icons) are part of FlowClone (MIT).
================================================================================
"""


def _norm(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _licence_texts(dist) -> list[str]:
    texts = []
    for f in dist.files or []:
        parts = [p.lower() for p in f.parts]
        name = parts[-1]
        if (".dist-info" in "/".join(parts) and
                (name.startswith(("license", "licence", "copying", "notice")) or "licenses" in parts)):
            try:
                texts.append(Path(f.locate()).read_text(encoding="utf-8", errors="replace").strip())
            except (OSError, ValueError):
                pass
    return texts


def main() -> None:
    out = ["FlowClone — third-party software notices", "",
           FIXED.format(py=sys.version.split()[0])]
    for dist in sorted(md.distributions(), key=lambda d: _norm(d.metadata["Name"])):
        name = _norm(dist.metadata["Name"])
        if name in BUILD_ONLY or name in EXCLUDED:
            continue
        lic = dist.metadata.get("License-Expression") or dist.metadata.get("License") or ""
        if not lic or len(lic) > 80:
            classifiers = [c.split("::")[-1].strip() for c in dist.metadata.get_all("Classifier") or []
                           if c.startswith("License")]
            lic = ", ".join(classifiers) or (lic[:80] + "…" if lic else "see licence text")
        out += ["", "-" * 80, f"{dist.metadata['Name']} {dist.version} — {lic}",
                dist.metadata.get("Home-page") or "", ""]
        out += _licence_texts(dist) or ["(no licence file shipped in the package metadata)"]
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
