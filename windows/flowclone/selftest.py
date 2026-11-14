"""`FlowClone.exe --selftest`: proves a build bundles everything it needs.

Run by build_exe.bat and the release workflow right after PyInstaller. Each check writes one
line to %TEMP%\\FlowClone-selftest.log; the exit code is 0 only if all pass. It needs no speech
model, microphone or network, and uses a temporary profile (never the user's settings).
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
        # Parakeet's audio preprocessor ships as package data; a build without it would only
        # fail after the 670 MB model download.
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

    def qt_ok():
        # After onnxruntime on purpose (MSVC runtime load order, see run.py).
        from PySide6.QtGui import QIcon
        from PySide6.QtWidgets import QApplication, QSystemTrayIcon

        from flowclone import paths
        from flowclone.ui import icons
        app = QApplication.instance() or QApplication([sys.argv[0]])
        for name in ("assets/flowclone.ico", "assets/flowclone-active.ico"):
            assert not QIcon(str(paths.resource(name))).isNull(), f"{name} not loadable"
        assert not icons.pixmap("mic", "#FFFFFF", 20).isNull(), "SVG icons not rendered"
        stub = _StubApp(app)
        from flowclone.ui.main_window import MainWindow
        from flowclone.ui.pill import Pill
        Pill(stub.pill, stub.bridge)
        win = MainWindow(stub)
        stub.window = win
        for key in ("home", "history", "dictionary", "snippets", "styles", "settings", "about"):
            win.show_page(key)
        _note.append(f"tray available: {QSystemTrayIcon.isSystemTrayAvailable()}")

    def icon_ok():
        from flowclone import paths
        assert paths.resource("assets/flowclone.ico").is_file(), "assets/flowclone.ico missing"

    def notices_ok():
        if getattr(sys, "frozen", False):
            base = Path(sys.executable).parent
            for name in ("LICENSE", "THIRD-PARTY-NOTICES.txt"):
                assert any(p.is_file() for p in (base / name, base / "_internal" / name)), name

    def excluded_absent():
        for name in ("faster_whisper", "ctranslate2", "hf_xet", "tkinter", "pystray", "PIL"):
            try:
                importlib.import_module(name)
            except ImportError:
                continue
            if getattr(sys, "frozen", False):
                raise AssertionError(f"{name} should not be bundled")

    def app_modules():
        imports("flowclone.app", "flowclone.model_store", "flowclone.stt", "flowclone.inject",
                "flowclone.hotkeys", "flowclone.llm", "flowclone.credstore", "flowclone.styles",
                "flowclone.ui.onboarding", "flowclone.ui.tray")

    return [
        ("python modules", lambda: imports("requests", "psutil", "pyperclip", "keyboard",
                                           "win32gui", "win32process", "win32cred", "winsound",
                                           "numpy")),
        ("truststore", truststore_ok),
        ("onnxruntime", onnxruntime_ok),
        ("onnx_asr + preprocessor data", onnx_asr_data_ok),
        ("sounddevice / PortAudio", sounddevice_ok),
        ("Qt window, pill and icons", qt_ok),
        ("icon", icon_ok),
        ("licence files", notices_ok),
        ("excluded packages absent", excluded_absent),
        ("FlowClone modules", app_modules),
    ]


_note: list[str] = []


class _StubApp:
    """Just enough of FlowApp for the window to build its pages."""

    def __init__(self, qt):
        from flowclone import config as cfg
        from flowclone.history import History
        from flowclone.lexicon import Lexicon
        from flowclone.llm import Polisher
        from flowclone.pill_state import PillController
        from flowclone.stats import Stats
        from flowclone.ui.bridge import Bridge
        from flowclone.ui.theme import Theme
        self.config = cfg.load_config()
        self.bridge = Bridge()
        self.theme = Theme(qt, "dark")
        self.history, self.stats, self.lexicon = History(), Stats(), Lexicon()
        self.polisher = Polisher(self.config["llm"])
        self.pill = PillController(self.bridge.emit_pill)
        self.model_status, self.model_failed, self.window = ("ready", 100), False, None

    def save_settings(self, mut, live=True):
        mut(self.config)

    def __getattr__(self, name):  # any other app action: a no-op
        return lambda *a, **k: None


def main() -> int:
    # A throwaway profile: the self-test must never read or change the user's settings.
    tmp = tempfile.mkdtemp(prefix="flowclone-selftest-")
    os.environ["APPDATA"] = os.path.join(tmp, "roaming")
    os.environ["LOCALAPPDATA"] = os.path.join(tmp, "local")
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
    lines.extend(f"note: {n}" for n in _note)
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
