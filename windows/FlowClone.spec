# -*- mode: python ; coding: utf-8 -*-
# PyInstaller build definition for FlowClone.exe (one-folder, windowed).
# Build: build_exe.bat   (or: python -m PyInstaller --noconfirm --clean FlowClone.spec)
import os
import re

from PyInstaller.utils.hooks import collect_data_files, copy_metadata
from PyInstaller.utils.win32.versioninfo import (
    FixedFileInfo, StringFileInfo, StringStruct, StringTable, VarFileInfo, VarStruct,
    VSVersionInfo,
)

HERE = SPECPATH  # noqa: F821 (defined by PyInstaller)

with open(os.path.join(HERE, "flowclone", "__init__.py"), encoding="utf-8") as f:
    VERSION = re.search(r'__version__\s*=\s*"(\d+)\.(\d+)\.(\d+)"', f.read()).groups()
VERSION_TUPLE = tuple(int(v) for v in VERSION) + (0,)
VERSION_STR = ".".join(VERSION) + ".0"

version_info = VSVersionInfo(
    ffi=FixedFileInfo(filevers=VERSION_TUPLE, prodvers=VERSION_TUPLE),
    kids=[
        StringFileInfo([StringTable("040904B0", [
            StringStruct("CompanyName", "FlowClone contributors"),
            StringStruct("FileDescription", "FlowClone - local voice dictation"),
            StringStruct("FileVersion", VERSION_STR),
            StringStruct("InternalName", "FlowClone"),
            StringStruct("LegalCopyright", "MIT License"),
            StringStruct("OriginalFilename", "FlowClone.exe"),
            StringStruct("ProductName", "FlowClone"),
            StringStruct("ProductVersion", VERSION_STR),
        ])]),
        VarFileInfo([VarStruct("Translation", [0x0409, 1200])]),
    ],
)

datas = (
    collect_data_files("onnx_asr")      # audio preprocessors (onnx/npz), read at runtime
    + copy_metadata("onnx_asr")          # onnx_asr reads its own version at import
    + [
        (os.path.join(HERE, "assets", "flowclone.ico"), "assets"),
        (os.path.join(HERE, "assets", "flowclone-active.ico"), "assets"),
        (os.path.join(HERE, "assets", "flowclone.png"), "assets"),
        (os.path.join(HERE, "assets", "icons", "*.svg"), os.path.join("assets", "icons")),
        (os.path.join(HERE, "licenses", "*.txt"), "licenses"),
        (os.path.join(HERE, "..", "LICENSE"), "."),
        (os.path.join(HERE, "THIRD-PARTY-NOTICES.txt"), "."),
    ]
)

# Qt modules FlowClone doesn't use (it is a Qt Widgets app): keep them out of the bundle.
QT_UNUSED = ("QtQml", "QtQuick", "QtNetwork", "QtOpenGL", "QtPdf", "QtWebEngine", "QtMultimedia",
             "QtDesigner", "QtUiTools", "QtSql", "QtTest", "QtConcurrent", "QtDBus", "QtXml")

a = Analysis(  # noqa: F821
    [os.path.join(HERE, "run.py")],
    pathex=[HERE],
    datas=datas,
    hiddenimports=[],
    excludes=[
        # Not in the exe: optional Whisper engine (+130 MB) and Hugging Face's Rust
        # downloader (FlowClone downloads its model itself, see model_store.py).
        "faster_whisper", "ctranslate2", "av", "tokenizers", "hf_xet",
        # Pulled in by onnxruntime's optional tools; never used at runtime.
        "torch", "onnx", "transformers",
        # The old Tk / pystray interface and the Pillow icon drawing (replaced by Qt).
        "tkinter", "_tkinter", "pystray", "PIL",
    ] + [f"PySide6.{m}" for m in QT_UNUSED],
    # PySide6 / Shiboken6 are LGPL: their Python parts ship as plain .py files and the Qt
    # libraries as separate DLLs in _internal, so they can be replaced.
    module_collection_mode={"PySide6": "py", "shiboken6": "py"},
    noarchive=False,
)

# Drop Qt libraries/plugins that a Widgets app never loads (software OpenGL, QML/Quick, PDF,
# networking, translations): about 60 MB.
_DROP = ("opengl32sw.dll", "d3dcompiler_47.dll", "qt6pdf", "qt6qml", "qt6quick", "qt6network",
         "qt6opengl", "qt6virtualkeyboard", "qt6labs", "qtuiotouchplugin", "qpdf", "qtvirtualkeyboard",
         "qtquick", "qmltooling")
a.binaries = [b for b in a.binaries if not any(d in b[0].lower() for d in _DROP)]
a.datas = [d for d in a.datas
           if not d[0].replace("/", "\\").lower().startswith(("pyside6\translations", "pyside6\qml"))]
pyz = PYZ(a.pure)  # noqa: F821

exe = EXE(  # noqa: F821
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="FlowClone",
    console=False,
    icon=os.path.join(HERE, "assets", "flowclone.ico"),
    version=version_info,
    upx=False,
)

coll = COLLECT(  # noqa: F821
    exe,
    a.binaries,
    a.datas,
    upx=False,
    name="FlowClone",
)
