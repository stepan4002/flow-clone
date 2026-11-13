"""FlowClone — a local, free Wispr Flow clone for Windows.

Hold the hotkey (default Ctrl+Win), speak, release: cleaned-up text appears in
whatever app is focused. Double-tap the hotkey for hands-free mode. Everything
runs on your machine — no cloud, no account, no word limits.
"""
import socket
import threading
import time

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

_SINGLE_INSTANCE_PORT = 47831


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
        self._engine_ready = threading.Event()
        self._quit = threading.Event()
        self._instance_lock: socket.socket | None = None

        self.recording = False
        self.hands_free = False
        self._press_time = 0.0
        self._last_tap = 0.0
        self._target_app = ""
        self._hands_free_deadline = 0.0

    # ---------- lifecycle ----------

    def start(self):
        if not self._acquire_single_instance():
            self.log.error("FlowClone is already running — exiting this copy.")
            return
        self.tray.start()
        threading.Thread(target=self._load_engine, daemon=True, name="model-loader").start()
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
        self._quit.set()

    def _acquire_single_instance(self) -> bool:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.bind(("127.0.0.1", _SINGLE_INSTANCE_PORT))
            s.listen(1)
            self._instance_lock = s  # held for the process lifetime
            return True
        except OSError:
            return False

    def _load_engine(self):
        self.overlay.set_state("loading")
        try:
            self.engine = create_engine(self.config)
            self._engine_ready.set()
            self.overlay.set_state("hidden")
            self.log.info("engine ready: %s", self.engine.name)
        except Exception as e:  # engine load failure is fatal for dictation
            self.overlay.set_state("processing", f"Model load failed: {e}", flash_ms=6000)
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
        self._press_time = time.time()
        self._start_recording()

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
        if self.recording:
            return
        self.hands_free = True
        self._hands_free_deadline = time.time() + self.config["hands_free_max_minutes"] * 60
        self._start_recording(state="hands_free")

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


def main():
    FlowApp().start()


if __name__ == "__main__":
    main()
