"""`FlowClone.exe --selftest`: proves a build bundles everything it needs.

Run by build_exe.bat and the release workflow right after PyInstaller. Each check writes
one line to %TEMP%\\FlowClone-selftest.log; the exit code is 0 only if all pass. It does not
need the speech model, a microphone or a network connection.
"""
import importlib
import os
import sys
import tempfile
import traceback
from pathlib import Path

LOG_PATH = Path(tempfile.gettempdir()) / "FlowClone-selftest.log"


def _checks():
    def imports(*names):
        for n in names:
            importlib.import_module(n)

    def truststore_ok():
        import truststore
        truststore.inject_into_ssl()

    def onnxruntime_ok():
        import onnxruntime
        assert "CPUExecutionProvider" in onnxruntime.get_available_providers()

    def onnx_asr_data_ok():
        # Parakeet's audio preprocessor ships as package data; a build without it would
        # only fail after the 670 MB model download.
        import importlib.resources
        import onnxruntime
        data = importlib.resources.files("onnx_asr.preprocessors") / "data" / "nemo128.onnx"
        with importlib.resources.as_file(data) as p:
            onnxruntime.InferenceSession(str(p), providers=["CPUExecutionProvider"])
        import importlib.metadata
        importlib.metadata.version("onnx-asr")  # onnx_asr reads its own version at import
        import onnx_asr  # noqa: F401

    def sounddevice_ok():
        import sounddevice
        sounddevice.query_devices()  # no devices (CI) is fine; a missing PortAudio DLL is not

    def tk_ok():
        import tkinter
        root = tkinter.Tk()
        root.withdraw()
        root.destroy()

    def icon_ok():
        from flowclone import paths
        assert paths.resource("assets/flowclone.ico").is_file(), "assets/flowclone.ico missing"

    def notices_ok():
        if getattr(sys, "frozen", False):
            base = Path(sys.executable).parent
            for name in ("LICENSE", "THIRD-PARTY-NOTICES.txt"):
                assert any(p.is_file() for p in (base / name, base / "_internal" / name)), name

    def excluded_absent():
        for name in ("faster_whisper", "ctranslate2", "hf_xet"):
            try:
                importlib.import_module(name)
            except ImportError:
                continue
            if getattr(sys, "frozen", False):
                raise AssertionError(f"{name} should not be bundled")

    def app_modules():
        imports("flowclone.app", "flowclone.hub", "flowclone.tray", "flowclone.overlay",
                "flowclone.model_store", "flowclone.stt", "flowclone.inject")

    return [
        ("python modules", lambda: imports("requests", "psutil", "pyperclip", "keyboard",
                                           "pystray", "pystray._win32", "PIL.ImageDraw",
                                           "win32gui", "win32process", "winsound", "numpy")),
        ("truststore", truststore_ok),
        ("onnxruntime", onnxruntime_ok),
        ("onnx_asr + preprocessor data", onnx_asr_data_ok),
        ("sounddevice / PortAudio", sounddevice_ok),
        ("tkinter", tk_ok),
        ("icon", icon_ok),
        ("licence files", notices_ok),
        ("excluded packages absent", excluded_absent),
        ("FlowClone modules", app_modules),
    ]


def main() -> int:
    failed = 0
    lines = [f"FlowClone selftest — {sys.executable} (frozen={getattr(sys, 'frozen', False)})"]
    for name, check in _checks():
        try:
            check()
            lines.append(f"PASS {name}")
        except Exception as e:
            failed += 1
            lines.append(f"FAIL {name}: {type(e).__name__}: {e}")
            lines.append(traceback.format_exc())
    lines.append("RESULT " + ("OK" if not failed else f"{failed} FAILED"))
    LOG_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if sys.stdout is not None:
        try:
            print("\n".join(lines))
        except Exception:
            pass
    return 1 if failed else 0


if __name__ == "__main__":
    os._exit(main())
