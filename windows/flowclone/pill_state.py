"""What the status pill shows — Qt-free, so it is tested on any machine.

Two halves:
- PillController: the thread-safe API every part of the app uses (dictation worker, keyboard
  hook, model loader): set_state(), state, schedule(), level(). It only records the request and
  hands it to `emit` (the Qt bridge in the app, a list in tests).
- PillModel: lives on the UI thread and decides what is on screen for each request, including
  the "sticky" download / model-error pill that a short flash message returns to.
"""
import threading
import time
from dataclasses import dataclass

# Shown even when the user turned the pill off: the one-time model download and its failure
# would otherwise be invisible.
ALWAYS_SHOWN = frozenset({"downloading", "model_error"})
STATES = ("hidden", "recording", "hands_free", "command", "processing", "loading",
          "downloading", "model_error", "info")
LEVEL_INTERVAL_S = 1 / 30


@dataclass(frozen=True)
class Display:
    visible: bool
    state: str
    message: str | None
    flash_ms: int = 0


class PillController:
    def __init__(self, emit=None, show_pill: bool = True, clock=time.monotonic):
        self.emit = emit or (lambda *a: None)
        self.show_pill = show_pill
        self._lock = threading.Lock()
        self._seq = 0
        self._requested = "hidden"
        self._clock = clock
        self._last_level = float("-inf")

    def set_state(self, state: str, message: str | None = None, flash_ms: int = 0):
        with self._lock:
            self._seq += 1
            self._requested = state
            seq = self._seq
        self.emit("state", state, message, int(flash_ms or 0), seq)

    @property
    def state(self) -> str:
        """The most recently requested state (maybe not on screen yet): a background thread must
        never decide from a state that a newer request already replaced."""
        with self._lock:
            return self._requested

    def flash_finished(self, seq: int, now_showing: str):
        """Called by the UI when a flash ends: only if nothing newer was requested meanwhile."""
        with self._lock:
            if self._seq == seq:
                self._requested = now_showing

    def schedule(self, fn):
        """Run fn() on the UI thread."""
        self.emit("call", fn)

    def level(self, rms: float):
        """Microphone level for the waveform (throttled)."""
        now = self._clock()
        if now - self._last_level >= LEVEL_INTERVAL_S:
            self._last_level = now
            self.emit("level", float(rms))


class PillModel:
    """UI-thread decisions (ported from the Tk overlay's _apply_state/_show/_flash_done)."""

    def __init__(self):
        self.sticky: tuple[str, str | None] | None = None
        self.flash_seq = -1

    def on_request(self, state: str, message: str | None, flash_ms: int, seq: int,
                   show_pill: bool) -> Display:
        if state in ALWAYS_SHOWN:
            self.sticky = (state, message)
        elif not flash_ms:
            self.sticky = None
        if flash_ms:
            self.flash_seq = seq  # the flash timer runs even when the pill is off
        return Display(self._visible(state, show_pill), state, message, flash_ms)

    def on_flash_done(self, show_pill: bool) -> Display:
        state, message = self.sticky or ("hidden", None)
        return Display(self._visible(state, show_pill), state, message)

    @staticmethod
    def _visible(state: str, show_pill: bool) -> bool:
        return state != "hidden" and (show_pill or state in ALWAYS_SHOWN)
