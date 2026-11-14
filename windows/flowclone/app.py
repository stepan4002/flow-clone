"""FlowClone — a local, free Wispr Flow clone for Windows.

Hold the hotkey (default Ctrl+Win), speak, release: cleaned-up text appears in
whatever app is focused. Double-tap the hotkey for hands-free mode. Everything
runs on your machine — no cloud, no account, no word limits.
"""
import getpass
import os
import socket
import threading
import time
import zlib

from . import audio, cleanup, config as cfg, inject
from .foreground import get_foreground_app
from .history import History
from .hotkeys import HotkeyManager
from .hub import open_hub
from .lexicon import Lexicon
from .llm import OllamaPolisher
from .logging_setup import setup_logging
from .overlay import Overlay
from .sounds import Sounds
from .stats import Stats
from .stt import create_engine
from .tray import Tray

def _user_tag() -> str:
    try:
        return f"{os.environ.get('USERDOMAIN', '')}\\{getpass.getuser()}".lower()
    except Exception:
        return "unknown"


# Loopback ports are shared by every signed-in Windows user: derive the port from the
# user so a second user's FlowClone doesn't find (and poke) the first user's copy.
_USER_TAG = _user_tag()
_SINGLE_INSTANCE_PORT = 47831 + zlib.crc32(_USER_TAG.encode("utf-8")) % 1000
# Named mutex created only by FlowClone.exe, so the installer can detect (and close) a
# running exe without confusing it with a source/pythonw run.
_EXE_MUTEX = "Local\\FlowCloneExe"


class FlowApp:
    def __init__(self):
        self.log = setup_logging()
        self.config = cfg.load_config()
        self.lexicon = Lexicon()
        self.history = History(self.config["history_limit"])
        self.stats = Stats()
        self.sounds = Sounds(self.config.get("sounds", True))
        self.overlay = Overlay(show_pill=self.config["overlay"]["enabled"])
        self.polisher = OllamaPolisher(self.config["llm"])
        self.recorder = audio.Recorder(
            sample_rate=self.config["audio"]["sample_rate"],
            device=self.config["audio"]["device"],
        )
        self.tray = Tray(self)

        self.engine = None
        self.model_failed = False
        self._model_pct = 0
        self._loader: threading.Thread | None = None
        self._loader_lock = threading.Lock()
        self._cancel_download = threading.Event()
        self._engine_ready = threading.Event()
        self._quit = threading.Event()
        self._instance_lock: socket.socket | None = None
        self._mutex = None

        self.recording = False
        self.hands_free = False
        self._press_time = 0.0
        self._last_tap = 0.0
        self._target_app = ""
        self._hands_free_deadline = 0.0

    # ---------- lifecycle ----------

    def start(self):
        if not self._acquire_single_instance():
            self.log.info("FlowClone is already running — asking it to show the Hub.")
            if not _ask_running_instance_to_show():
                from .crash import show_fatal
                show_fatal("FlowClone could not start: it looks like it is already running, "
                           f"or another program uses its local port {_SINGLE_INSTANCE_PORT}."
                           "\n\nLook for the microphone icon in the system tray.")
            return
        self.tray.start()
        try:
            from .hub import ensure_autostart_current
            ensure_autostart_current()
        except Exception:
            self.log.exception("autostart check failed")
        self.retry_model_load()
        self.hotkeys = HotkeyManager(
            self.config,
            on_press=self._on_ptt_press,
            on_release=self._on_ptt_release,
            on_cancel=self._on_cancel,
            on_paste_last=self.paste_last,
            on_copy_last=self.copy_last,
        )
        combo = "+".join(self.config["hotkeys"]["push_to_talk"])
        self.log.info("FlowClone running. Hold [%s] to dictate, double-tap for "
                      "hands-free, Esc to cancel. Tray icon has the menu.", combo)
        try:
            while not self._quit.is_set():
                self._check_hands_free_deadline()
                time.sleep(0.2)
        except KeyboardInterrupt:
            pass
        self.tray.stop()

    def quit(self):
        self._cancel_download.set()  # a running download stops at the next chunk (resumable)
        self._quit.set()
        # Stop answering "show" right away, so a relaunch during shutdown starts fresh.
        lock, self._instance_lock = self._instance_lock, None
        if lock is not None:
            try:
                lock.close()
            except OSError:
                pass

    def _acquire_single_instance(self) -> bool:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.bind(("127.0.0.1", _SINGLE_INSTANCE_PORT))
            s.listen(4)
            self._instance_lock = s  # held for the process lifetime
        except OSError:
            return False
        threading.Thread(target=self._serve_instance_requests, daemon=True,
                         name="single-instance").start()
        from . import paths
        if paths.is_frozen():
            try:
                import ctypes
                self._mutex = ctypes.windll.kernel32.CreateMutexW(None, False, _EXE_MUTEX)
            except Exception:
                self.log.warning("could not create the single-instance mutex", exc_info=True)
        return True

    def _serve_instance_requests(self):
        """A second launch connects here and sends 'show': bring up the Hub."""
        lock = self._instance_lock
        while lock is not None and not self._quit.is_set():
            try:
                conn, _addr = lock.accept()
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
        self._model_pct = pct
        text = f"Downloading speech model… {pct}% ({done // 1_000_000}/{total // 1_000_000} MB)"
        self.tray.set_tooltip(f"FlowClone — downloading speech model {pct}%")
        # Never paint over recording / transcribing feedback.
        if self.overlay.state in ("hidden", "loading", "downloading"):
            self.overlay.set_state("downloading", text)

    def _load_engine(self):
        from .model_store import ModelError
        self.overlay.set_state("loading")
        try:
            self.engine = create_engine(
                self.config, progress_cb=self._on_model_progress,
                cancel_event=self._cancel_download,
                on_fallback=lambda msg: self.overlay.set_state(
                    "processing", f"{msg} — using Parakeet", flash_ms=5000))
            self._engine_ready.set()
            self.tray.set_tooltip("FlowClone")
            if self.overlay.state in ("loading", "downloading"):
                self.overlay.set_state("hidden")
            self.log.info("engine ready: %s", self.engine.name)
        except ModelError as e:
            if self._quit.is_set():
                return
            self.model_failed = True
            self.tray.refresh_menu()
            self.tray.set_tooltip(f"FlowClone — speech model download failed: {e.short}")
            self.overlay.set_state("model_error", f"Model download failed: {e.short}")
            self.log.error("speech model unavailable: %s (to install it by hand, put the "
                           "model files into %s)", e, _model_target())
        except Exception as e:  # engine load failure is fatal for dictation
            if self._quit.is_set():
                return
            self.model_failed = True
            self.tray.refresh_menu()
            self.tray.set_tooltip("FlowClone — speech model failed to load (see log)")
            self.overlay.set_state("model_error", f"Model load failed: {e}")
            self.log.exception("engine load failed")

    def open_hub(self):
        self.overlay.schedule(lambda: open_hub(self))

    def apply_live_settings(self):
        """Called by the Hub after saving — applies what doesn't need a restart."""
        self.sounds.enabled = self.config.get("sounds", True)
        self.overlay.show_pill = self.config["overlay"]["enabled"]
        self.polisher = OllamaPolisher(self.config["llm"])
        self.log.info("settings applied")

    # ---------- push-to-talk state machine ----------

    def _on_ptt_press(self):
        if self.hands_free:
            self._stop_hands_free()
            return
        if self.recording:
            return
        if not self._engine_usable():
            return
        self._press_time = time.time()
        self._start_recording()

    def _engine_usable(self) -> bool:
        """False (with feedback) while the model is downloading, loading or failed:
        don't record into the void."""
        if self._engine_ready.is_set():
            return True
        if self.model_failed:
            msg = "Speech model missing — tray menu → Retry download"
        elif self._model_pct:
            msg = f"Speech model not ready yet ({self._model_pct}%)"
        else:
            msg = "Speech model still loading…"
        self.overlay.set_state("processing", msg, flash_ms=2500)
        self.sounds.play("error")
        return False

    def _on_ptt_release(self):
        if self.hands_free or not self.recording:
            return
        duration = time.time() - self._press_time
        audio_data = self._stop_recording()
        if duration < self.config["min_recording_seconds"]:
            # Too short to be speech: treat as a tap (double-tap = hands-free).
            now = time.time()
            if self.config["double_tap_hands_free"] and now - self._last_tap < 0.6:
                self._last_tap = 0.0
                self._start_hands_free()
            else:
                self._last_tap = now
                self.overlay.set_state("hidden")
            return
        self._process_async(audio_data)

    def _on_cancel(self):
        if self.hands_free:
            self._stop_hands_free(discard=True)
        elif self.recording:
            self._stop_recording()
            self.overlay.set_state("hidden")
            self.log.info("dictation cancelled")

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
        self._start_recording(state="hands_free")
        self.hands_free = self.recording  # stays off if the mic failed to start

    def _stop_hands_free(self, discard: bool = False):
        self.hands_free = False
        audio_data = self._stop_recording()
        if discard:
            self.overlay.set_state("hidden")
        else:
            self._process_async(audio_data)

    def _check_hands_free_deadline(self):
        if self.hands_free and time.time() > self._hands_free_deadline:
            self.log.info("hands-free session cap reached — transcribing")
            self._stop_hands_free()

    # ---------- recording / processing ----------

    def _start_recording(self, state: str = "recording"):
        self._target_app = get_foreground_app()
        try:
            self.recorder.start()
        except Exception as e:
            self.overlay.set_state("processing", f"Mic error: {e}", flash_ms=4000)
            self.sounds.play("error")
            self.log.error("mic error: %s", e)
            return
        self.recording = True
        self.tray.set_active(True)
        self.sounds.play("start")
        self.overlay.set_state(state)

    def _stop_recording(self):
        self.recording = False
        self.tray.set_active(False)
        self.sounds.play("stop")
        return self.recorder.stop()

    def _process_async(self, audio_data):
        threading.Thread(target=self._process, args=(audio_data,), daemon=True).start()

    def _process(self, audio_data):
        if audio_data.size == 0:
            self.overlay.set_state("hidden")
            return
        self.overlay.set_state("processing")
        if not self._engine_ready.wait(timeout=120):
            self.overlay.set_state("processing", "Model not loaded", flash_ms=3000)
            return
        t0 = time.time()
        try:
            raw = self.engine.transcribe(
                audio_data,
                self.config["audio"]["sample_rate"],
                hint_words=self.lexicon.words,
                language=self.config.get("language"),
            )
        except Exception as e:
            self.overlay.set_state("processing", f"Transcription failed: {e}", flash_ms=4000)
            self.sounds.play("error")
            self.log.exception("transcription failed")
            return
        if not raw.strip():
            self.overlay.set_state("processing", "No speech detected", flash_ms=1500)
            return

        app_name = self._target_app
        is_casual = app_name in [a.lower() for a in self.config["casual_apps"]]

        text = self.lexicon.apply(raw)
        text, press_enter = cleanup.clean(text, self.config["cleanup"], is_casual)
        if self.config["llm"]["enabled"]:
            text = self.polisher.polish(
                text, app_name if self.config["llm"]["tone_matching"] else None)
        if not text:
            self.overlay.set_state("hidden")
            return

        mode = self.config["injection"]["mode"]
        if app_name in [a.lower() for a in self.config["injection"]["type_mode_apps"]]:
            mode = "type"
        inject.insert_text(
            text, mode=mode,
            restore_delay_ms=self.config["injection"]["restore_clipboard_delay_ms"],
            press_enter=press_enter,
        )
        self.history.append(text, raw, app_name)
        self.stats.add(text)
        self.overlay.set_state("hidden")
        self.log.info("-> %s (%.1fs): %s", app_name or "unknown app",
                      time.time() - t0, text[:80] + ("…" if len(text) > 80 else ""))

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


def _handshake() -> bytes:
    return f"flowclone {_USER_TAG}\n".encode("utf-8")


def _ask_running_instance_to_show() -> bool:
    """True only if a FlowClone of this same user answered (not some other program)."""
    try:
        with socket.create_connection(("127.0.0.1", _SINGLE_INSTANCE_PORT), timeout=2) as c:
            c.settimeout(3)
            c.sendall(b"show\n")
            return c.recv(256) == _handshake()
    except OSError:
        return False


def _model_target():
    from .model_store import target_dir
    return target_dir()


def main():
    FlowApp().start()


if __name__ == "__main__":
    main()
