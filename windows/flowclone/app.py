"""FlowClone — free, local voice dictation for Windows.

Hold the hotkey (default Ctrl+Win), speak, release: cleaned-up text appears in whatever app is
focused. Double-tap for hands-free; add Shift for Command Mode (edit selected text by voice).
Speech recognition runs on this computer; nothing is uploaded unless the user turns on a cloud
AI provider for rewriting.

Threads: Qt owns the main thread (window, pill, tray). The keyboard hook, the microphone
callback, the model loader, the single-instance listener and one worker per dictation never
touch widgets: they go through the PillController / TrayIcon / Bridge signals.
"""
import getpass
import logging
import os
import socket
import subprocess
import sys
import threading
import time
import zlib

from . import audio, config as cfg, inject, paths, styles
from .foreground import get_foreground_app
from .history import History
from .lexicon import Lexicon
from .llm import Polisher
from .logging_setup import setup_logging
from .sounds import Sounds
from .stats import Stats, count_words
from .stt import create_engine

log = logging.getLogger("flowclone")


def _user_tag() -> str:
    try:
        return f"{os.environ.get('USERDOMAIN', '')}\\{getpass.getuser()}".lower()
    except Exception:
        return "unknown"


# Loopback ports are shared by every signed-in Windows user: derive the port from the user so a
# second user's FlowClone doesn't find (and poke) the first user's copy.
_USER_TAG = _user_tag()
_SINGLE_INSTANCE_PORT = 47831 + zlib.crc32(_USER_TAG.encode("utf-8")) % 1000
# Named mutex created only by FlowClone.exe, so the installer can detect (and close) a running
# exe without confusing it with a source/pythonw run.
_EXE_MUTEX = "Local\\FlowCloneExe"


# ---------------------------------------------------------------- single instance

class Instance:
    """The listening socket (+ mutex for the exe) that marks the running FlowClone."""

    def __init__(self, sock, mutex):
        self.sock, self.mutex = sock, mutex

    def close(self):
        sock, self.sock = self.sock, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        mutex, self.mutex = self.mutex, None
        if mutex:
            try:
                import ctypes
                ctypes.windll.kernel32.CloseHandle(mutex)
            except Exception:
                pass


def acquire_single_instance(retry_s: float = 0.0) -> Instance | None:
    deadline = time.monotonic() + retry_s
    while True:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.bind(("127.0.0.1", _SINGLE_INSTANCE_PORT))
            s.listen(4)
            break
        except OSError:
            s.close()
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.25)  # a restart: the previous process is still exiting
    mutex = None
    if paths.is_frozen():
        try:
            import ctypes
            mutex = ctypes.windll.kernel32.CreateMutexW(None, False, _EXE_MUTEX)
        except Exception:
            log.warning("could not create the single-instance mutex", exc_info=True)
    return Instance(s, mutex)


def _handshake() -> bytes:
    return f"flowclone {_USER_TAG}\n".encode("utf-8")


def ask_running_instance_to_show() -> bool:
    """True only if a FlowClone of this same user answered (not some other program)."""
    try:
        import ctypes
        ctypes.windll.user32.AllowSetForegroundWindow(-1)  # ASFW_ANY: let it come to the front
    except Exception:
        pass
    try:
        with socket.create_connection(("127.0.0.1", _SINGLE_INSTANCE_PORT), timeout=2) as c:
            c.settimeout(3)
            c.sendall(b"show\n")
            return c.recv(256) == _handshake()
    except OSError:
        return False


# ---------------------------------------------------------------- the app

class FlowApp:
    def __init__(self, qt_app, bridge, theme, pill, tray, instance: Instance, config: dict):
        self.qt, self.bridge, self.theme, self.pill, self.tray = qt_app, bridge, theme, pill, tray
        self.overlay = pill  # older name, same API
        self.instance = instance
        self.config = config
        self.lexicon = Lexicon()
        self.history = History(self.config["history_limit"])
        self.stats = Stats()
        self.sounds = Sounds(self.config.get("sounds", True))
        self.polisher = Polisher(self.config["llm"])
        self.recorder = self._make_recorder()
        self.window = None
        self.onboarding = None
        self.hotkeys = None
        self.test_mode = False          # onboarding "try it": don't count it in history/stats

        self.engine = None
        self.model_failed = False
        self.model_status = ("loading", 0)
        self._model_pct = 0
        self._loader: threading.Thread | None = None
        self._loader_lock = threading.Lock()
        self._settings_lock = threading.Lock()
        self._cancel_download = threading.Event()
        self._engine_ready = threading.Event()
        self._quit = threading.Event()

        self.recording = False
        self.hands_free = False
        self.command = False
        self._press_time = 0.0
        self._last_tap = 0.0
        self._target_app = ""
        self._target_hwnd = 0
        self._last_target_hwnd = 0
        self._hands_free_deadline = 0.0

        bridge.show_window.connect(self._show_window)

    def _make_recorder(self):
        return audio.Recorder(sample_rate=self.config["audio"]["sample_rate"],
                              device=self.config["audio"]["device"], on_level=self.pill.level)

    # ---------- lifecycle (UI thread) ----------

    def start(self):
        from PySide6.QtCore import QTimer
        threading.Thread(target=self._serve_instance_requests, daemon=True,
                         name="single-instance").start()
        try:
            from .autostart import ensure_autostart_current
            ensure_autostart_current()
        except Exception:
            log.exception("autostart check failed")
        self.retry_model_load()
        from .hotkeys import HotkeyManager
        self.hotkeys = HotkeyManager(
            self.config, on_press=self._on_ptt_press, on_release=self._on_ptt_release,
            on_cancel=self._on_cancel, on_paste_last=self.paste_last,
            on_copy_last=self.copy_last, on_command=self._on_command)
        self._cap_timer = QTimer()
        self._cap_timer.setInterval(200)
        self._cap_timer.timeout.connect(self._check_hands_free_deadline)
        self._cap_timer.start()
        combo = "+".join(self.config["hotkeys"]["push_to_talk"])
        log.info("FlowClone running. Hold [%s] to dictate, double-tap for hands-free, Esc to "
                 "cancel.", combo)
        if not self.config["ui"].get("onboarding_done", True):
            from .ui.onboarding import Onboarding
            self.onboarding = Onboarding(self)
            self.onboarding.show()

    def _combo_text(self) -> str:
        names = {"ctrl": "Ctrl", "windows": "Win", "shift": "Shift", "alt": "Alt"}
        return " + ".join(names.get(k, k.capitalize()) for k in self.config["hotkeys"]["push_to_talk"])

    def quit(self):
        self._cancel_download.set()  # a running download stops at the next chunk (resumable)
        self._quit.set()
        self.instance.close()        # a relaunch during shutdown starts fresh
        if self.hotkeys is not None:
            self.hotkeys.stop()
        if self.recording:
            try:
                self.recorder.stop()
            except Exception:
                pass
        self.tray.stop()
        self.bridge.call.emit(self.qt.quit)

    def restart(self):
        """Start a new FlowClone (it waits for this one to let go) and quit this one."""
        cmd = paths.launch_command() + " --restarted"
        self.quit()
        try:
            subprocess.Popen(cmd, close_fds=True, creationflags=getattr(subprocess, "DETACHED_PROCESS", 0))
        except OSError:
            log.exception("restart failed")

    def _serve_instance_requests(self):
        """A second launch connects here and sends 'show': bring up the window."""
        sock = self.instance.sock
        while sock is not None and not self._quit.is_set():
            try:
                conn, _addr = sock.accept()
            except OSError:
                return
            with conn:
                try:
                    conn.settimeout(2)
                    if conn.recv(64).strip() == b"show" and not self._quit.is_set():
                        conn.sendall(_handshake())
                        self.open_hub()
                except OSError:
                    pass

    # ---------- window ----------

    def open_hub(self, page: str = "home"):
        """Any thread."""
        self.bridge.show_window.emit(page)

    def _show_window(self, page):
        from .ui.main_window import MainWindow
        if self.window is None:
            self.window = MainWindow(self)
        self.window.open(page)

    def flash(self, message: str, ms: int = 1400):
        self.pill.set_state("info", message, flash_ms=ms)

    # ---------- settings ----------

    def save_settings(self, mutator, live: bool = True):
        with self._settings_lock:
            mutator(self.config)
            cfg.save_config(self.config)
        if live:
            self.apply_live_settings()

    def apply_live_settings(self):
        """Apply what doesn't need a restart (hotkey and engine changes do)."""
        c = self.config
        self.sounds.enabled = c.get("sounds", True)
        self.pill.show_pill = c["overlay"]["enabled"]
        if self.theme.mode != c["ui"].get("theme", "system"):
            self.theme.set_mode(c["ui"].get("theme", "system"))
        self.rebuild_polisher()
        if self.hotkeys is not None:
            self.hotkeys.set_command_key(c["hotkeys"].get("command_modifier"))
        if not self.recording and self.recorder.device != c["audio"]["device"]:
            self.recorder = self._make_recorder()
        log.info("settings applied")

    def rebuild_polisher(self):
        self.polisher = Polisher(self.config["llm"])

    def suspend_hotkeys(self):
        if self.hotkeys is not None:
            self.hotkeys.suspend()

    def resume_hotkeys(self):
        if self.hotkeys is not None:
            self.hotkeys.resume()

    # ---------- speech model ----------

    def _set_model_status(self, state: str, pct: int = 0):
        self.model_status = (state, pct)
        self.bridge.model_status.emit(state, pct)

    def retry_model_load(self):
        """Start (or restart after a failure) loading the speech engine in the background."""
        with self._loader_lock:
            if self.engine is not None or (self._loader and self._loader.is_alive()):
                return
            self.model_failed = False
            self.tray.refresh_menu()
            self._loader = threading.Thread(target=self._load_engine, daemon=True,
                                            name="model-loader")
            self._loader.start()

    def _on_model_progress(self, done: int, total: int):
        pct = int(done * 100 / total) if total else 0
        if pct != self._model_pct or self.model_status[0] != "downloading":
            self._model_pct = pct
            self._set_model_status("downloading", pct)
            self.tray.set_tooltip(f"FlowClone — downloading speech model {pct}%")
        # Never paint over recording / transcribing feedback.
        if self.pill.state in ("hidden", "loading", "downloading"):
            self.pill.set_state("downloading", f"Downloading speech model · {pct}%")

    def _load_engine(self):
        from .model_store import ModelError
        self._set_model_status("loading")
        self.pill.set_state("loading")
        try:
            self.engine = create_engine(
                self.config, progress_cb=self._on_model_progress,
                cancel_event=self._cancel_download,
                on_fallback=lambda msg: self.pill.set_state(
                    "info", f"{msg} — using Parakeet", flash_ms=5000))
            self._engine_ready.set()
            self.tray.set_tooltip("FlowClone — ready")
            self._set_model_status("ready", 100)
            if self.pill.state in ("loading", "downloading"):
                self.pill.set_state("hidden")
            log.info("engine ready: %s", self.engine.name)
        except ModelError as e:
            if self._quit.is_set():
                return
            self.model_failed = True
            self.tray.refresh_menu()
            self.tray.set_tooltip(f"FlowClone — speech model download failed: {e.short}")
            self._set_model_status("error")
            self.pill.set_state("model_error", f"Model download failed: {e.short}")
            log.error("speech model unavailable: %s (to install it by hand, put the model files "
                      "into %s)", e, _model_target())
        except Exception as e:  # engine load failure is fatal for dictation
            if self._quit.is_set():
                return
            self.model_failed = True
            self.tray.refresh_menu()
            self.tray.set_tooltip("FlowClone — speech model failed to load (see log)")
            self._set_model_status("error")
            self.pill.set_state("model_error", f"Model load failed: {type(e).__name__}")
            log.exception("engine load failed")

    # ---------- push-to-talk state machine (keyboard hook thread) ----------

    def _on_ptt_press(self, command: bool = False):
        if self.hands_free:
            self._stop_hands_free()
            return
        if self.recording:
            return
        if not self._engine_usable():
            return
        if command and not self._command_ready():
            return
        self.command = command
        self._press_time = time.time()
        self._start_recording(state="command" if command else "recording")

    def _on_command(self):
        """The command key was added while holding the hotkey."""
        if self.recording and not self.hands_free and not self.command and self._command_ready():
            self.command = True
            self.pill.set_state("command")

    def _command_ready(self) -> bool:
        if self.config["llm"].get("enabled"):
            return True
        self.pill.set_state("info", "Command Mode needs AI — Settings → AI", flash_ms=3000)
        self.sounds.play("error")
        return False

    def _engine_usable(self) -> bool:
        """False (with feedback) while the model is downloading, loading or failed: don't
        record into the void."""
        if self._engine_ready.is_set():
            return True
        if self.model_failed:
            msg = "Speech model missing — open FlowClone → Settings → Retry"
        elif self._model_pct:
            msg = f"Speech model not ready yet ({self._model_pct}%)"
        else:
            msg = "Speech model still loading…"
        self.pill.set_state("info", msg, flash_ms=2500)
        self.sounds.play("error")
        return False

    def _on_ptt_release(self):
        if self.hands_free or not self.recording:
            return
        duration = time.time() - self._press_time
        command = self.command
        self.command = False
        audio_data = self._stop_recording()
        if duration < self.config["min_recording_seconds"]:
            # Too short to be speech: treat as a tap (double-tap = hands-free).
            now = time.time()
            if not command and self.config["double_tap_hands_free"] and now - self._last_tap < 0.6:
                self._last_tap = 0.0
                self._start_hands_free()
            else:
                self._last_tap = now
                self.pill.set_state("hidden")
            return
        self._process_async(audio_data, duration, command)

    def _on_cancel(self):
        if self.hands_free:
            self._stop_hands_free(discard=True)
        elif self.recording:
            self.command = False
            self._stop_recording()
            self.pill.set_state("hidden")
            log.info("dictation cancelled")

    # ---------- hands-free ----------

    def toggle_hands_free(self):
        if self.hands_free:
            self._stop_hands_free()
        else:
            self._start_hands_free()

    def _start_hands_free(self):
        if self.recording or not self._engine_usable():
            return
        self._hands_free_deadline = time.time() + self.config["hands_free_max_minutes"] * 60
        self._press_time = time.time()
        self._start_recording(state="hands_free")
        self.hands_free = self.recording  # stays off if the mic failed to start

    def _stop_hands_free(self, discard: bool = False):
        self.hands_free = False
        duration = time.time() - self._press_time
        audio_data = self._stop_recording()
        if discard:
            self.pill.set_state("hidden")
        else:
            self._process_async(audio_data, duration, False)

    def _check_hands_free_deadline(self):
        if self.hands_free and time.time() > self._hands_free_deadline:
            log.info("hands-free session cap reached — transcribing")
            self._stop_hands_free()

    # ---------- recording / processing ----------

    def _start_recording(self, state: str = "recording"):
        self._target_app = get_foreground_app()
        self._target_hwnd = inject.foreground_window()
        try:
            self.recorder.start()
        except Exception as e:
            self.pill.set_state("info", f"Microphone error: {type(e).__name__}", flash_ms=4000)
            self.sounds.play("error")
            log.error("mic error: %s", e)
            return
        self.recording = True
        self.tray.set_active(True)
        self.sounds.play("start")
        self.pill.set_state(state)

    def _stop_recording(self):
        self.recording = False
        self.tray.set_active(False)
        self.sounds.play("stop")
        return self.recorder.stop()

    def _process_async(self, audio_data, seconds: float, command: bool):
        threading.Thread(target=self._process, args=(audio_data, seconds, command),
                         daemon=True, name="dictation").start()

    def _process(self, audio_data, seconds: float = 0.0, command: bool = False):
        if audio_data.size == 0:
            self.pill.set_state("hidden")
            return
        self.pill.set_state("processing")
        if not self._engine_ready.wait(timeout=120):
            self.pill.set_state("info", "Speech model not loaded", flash_ms=3000)
            return
        self.lexicon.reload_if_changed()
        t0 = time.time()
        try:
            raw = self.engine.transcribe(
                audio_data, self.config["audio"]["sample_rate"],
                hint_words=self.lexicon.words, language=self.config.get("language"))
        except Exception as e:
            self.pill.set_state("info", f"Transcription failed: {type(e).__name__}",
                                flash_ms=4000)
            self.sounds.play("error")
            log.exception("transcription failed")
            return
        if not raw.strip():
            self.pill.set_state("info", "No speech detected", flash_ms=1500)
            return

        app_name = self._target_app
        if command:
            self._run_command(raw, app_name, seconds, t0)
            return
        style = styles.style_for(app_name, self.config)
        text = self.lexicon.apply(raw)
        text, press_enter = styles.apply_rules(text, style, self.config["cleanup"])
        if self.config["llm"]["enabled"] and text:
            instruction = styles.llm_instruction(
                style if self.config["llm"].get("tone_matching", True) else "clean")
            text = self.polisher.rewrite(text, instruction) or text
        if not text:
            self.pill.set_state("hidden")
            return
        self._insert(text, app_name, press_enter)
        self._record(text, raw, app_name, seconds, style)
        self.pill.set_state("hidden")
        log.info("dictation -> %s (%.1f s audio, %.1f s total, %d words, %s style)",
                 app_name or "unknown app", seconds, time.time() - t0, count_words(text), style)

    def _run_command(self, spoken: str, app_name: str, seconds: float, t0: float):
        """Command Mode: apply the spoken instruction to the selected text (or, with nothing
        selected, write what was asked for)."""
        deadline = time.monotonic() + 0.5
        while self.hotkeys is not None and self.hotkeys.keys_down() and time.monotonic() < deadline:
            time.sleep(0.02)  # never send Ctrl+C while Win/Shift are still held
        if inject.foreground_is_elevated():
            self.pill.set_state("info", "Can't edit text in admin windows", flash_ms=3000)
            self.sounds.play("error")
            return
        self.pill.set_state("processing", "Working on it…")
        selection = inject.read_selection()
        instruction = self.lexicon.apply(spoken)
        out = self.polisher.rewrite(instruction, "", selection=selection, mode="command")
        if out is None:
            self.pill.set_state("info", "AI didn't answer — nothing changed", flash_ms=3000)
            self.sounds.play("error")
            return
        self._insert(out, app_name, False)
        self._record(out, spoken, app_name, seconds, "command")
        self.pill.set_state("hidden")
        log.info("command -> %s (%s selection, %.1f s, %d words)", app_name or "unknown app",
                 "with" if selection else "no", time.time() - t0, count_words(out))

    def _insert(self, text, app_name, press_enter):
        mode = self.config["injection"]["mode"]
        if app_name in [a.lower() for a in self.config["injection"]["type_mode_apps"]]:
            mode = "type"
        inject.insert_text(text, mode=mode,
                           restore_delay_ms=self.config["injection"]["restore_clipboard_delay_ms"],
                           press_enter=press_enter)
        self._last_target_hwnd = self._target_hwnd

    def _record(self, text, raw, app_name, seconds, style):
        if self.test_mode:
            return
        self.history.append(text, raw, app_name, duration_s=seconds, style=style)
        self.stats.add(text, seconds=seconds, app=app_name)
        self.bridge.dictation_done.emit()

    # ---------- history shortcuts ----------

    def paste_last(self):
        last = self.history.last()
        if last:
            inject.insert_text(
                last, mode=self.config["injection"]["mode"],
                restore_delay_ms=self.config["injection"]["restore_clipboard_delay_ms"])

    def copy_last(self):
        last = self.history.last()
        if last:
            inject.copy_to_clipboard(last)

    def paste_into_last_app(self, text: str, app_exe: str = ""):
        """From the History page: put the text into the app of the last dictation."""
        hwnd = self._last_target_hwnd

        def work():
            if self.window is not None:
                self.bridge.call.emit(self.window.showMinimized)
            time.sleep(0.3)
            if not inject.focus_window(hwnd):
                inject.copy_to_clipboard(text)
                self.flash("That window is gone — copied instead", 2500)
                return
            time.sleep(0.15)
            self._insert(text, app_exe, False)
        threading.Thread(target=work, daemon=True).start()


def _model_target():
    from .model_store import target_dir
    return target_dir()


def _install_qt_logging():
    from PySide6.QtCore import QtMsgType, qInstallMessageHandler

    def handler(mode, context, message):
        if mode == QtMsgType.QtFatalMsg:
            log.critical("Qt: %s", message)
        elif mode in (QtMsgType.QtCriticalMsg, QtMsgType.QtWarningMsg):
            log.warning("Qt: %s", message)
        else:
            log.debug("Qt: %s", message)
    qInstallMessageHandler(handler)


def main() -> int:
    setup_logging()
    restarted = "--restarted" in sys.argv
    instance = acquire_single_instance(retry_s=8 if restarted else 0)
    if instance is None:
        log.info("FlowClone is already running — asking it to show its window.")
        if ask_running_instance_to_show():
            return 0
        from .crash import show_fatal
        show_fatal("FlowClone could not start: it looks like it is already running, or another "
                   f"program uses its local port {_SINGLE_INSTANCE_PORT}.\n\n"
                   "Look for the microphone icon in the system tray.")
        return 1

    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("FlowClone.App")
        except Exception:
            pass
    from PySide6.QtCore import QTimer
    from PySide6.QtGui import QIcon
    from PySide6.QtWidgets import QApplication

    from .pill_state import PillController
    from .ui.bridge import Bridge
    from .ui.pill import Pill
    from .ui.theme import Theme
    from .ui.tray import TrayIcon

    _install_qt_logging()
    qt = QApplication(sys.argv[:1])
    qt.setApplicationName("FlowClone")
    qt.setApplicationDisplayName("FlowClone")
    qt.setQuitOnLastWindowClosed(False)
    qt.setWindowIcon(QIcon(str(paths.resource("assets/flowclone.ico"))))
    config = cfg.load_config()
    theme = Theme(qt, config["ui"].get("theme", "system"))
    bridge = Bridge()
    pill_ctl = PillController(bridge.emit_pill, show_pill=config["overlay"]["enabled"])
    pill = Pill(pill_ctl, bridge)
    tray = TrayIcon(bridge)
    app = FlowApp(qt, bridge, theme, pill_ctl, tray, instance, config)
    tray.set_app(app)
    tray.show()
    QTimer.singleShot(0, app.start)
    code = qt.exec()
    del pill
    return code


if __name__ == "__main__":
    sys.exit(main())
