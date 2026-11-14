"""The 'Flow Bar' — a rounded always-on-top status pill near the bottom of the
screen — plus the shared tkinter thread that also hosts the Hub window.

All tkinter work happens on this module's thread. Other threads use set_state()
for the pill and schedule() to run UI callables (e.g. opening the Hub).
"""
import queue
import logging
import threading
import tkinter as tk

log = logging.getLogger("flowclone")

_STATES = {
    "recording": ("#e5484d", "●  Listening…"),
    "hands_free": ("#e5484d", "●  Hands-free — hotkey or Esc to stop"),
    "processing": ("#f0a020", "…  Transcribing"),
    "loading": ("#4a90d9", "⏳  Loading speech model…"),
    "downloading": ("#4a90d9", "⬇  Downloading speech model…"),
}
# States shown even when the user turned the pill off: the one-time model download
# and its failure would otherwise be invisible.
_ALWAYS_SHOWN = {"downloading", "model_error"}
_PILL_W, _PILL_H = 360, 40
_BG = "#1c1c1e"
_TRANSPARENT = "#010203"  # color-keyed to transparent for rounded corners


class Overlay:
    def __init__(self, show_pill: bool = True):
        self.show_pill = show_pill
        self._q: queue.Queue = queue.Queue()
        self._pulse_on = False
        self._state = "hidden"          # last state applied on the tk thread
        self._requested = "hidden"      # last state requested by any thread
        self._seq = 0                   # request counter (guarded by _lock)
        self._lock = threading.Lock()
        self._flash_id = None
        self._flash_seq = -1
        # The download / model-error pill stays up: a flash shown over it returns to it.
        self._sticky: tuple[str, str | None] | None = None
        self.root: tk.Tk | None = None
        self._ready = threading.Event()
        threading.Thread(target=self._run, daemon=True, name="ui").start()
        self._ready.wait(timeout=10)

    # ---- thread-safe API ----

    def set_state(self, state: str, message: str | None = None, flash_ms: int = 0):
        with self._lock:
            self._seq += 1
            self._requested = state
            self._q.put(("state", state, message, flash_ms, self._seq))

    @property
    def state(self) -> str:
        """The most recently requested state (not yet necessarily on screen), so a
        background thread never decides from a state that a newer request replaced."""
        with self._lock:
            return self._requested

    def schedule(self, fn):
        """Run fn() on the tkinter thread (used by tray to open the Hub)."""
        self._q.put(("call", fn, None, None, None))

    # ---- tk thread ----

    def _run(self):
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title("FlowClone")
        # Tk swallows callback errors to stderr, which is null in FlowClone.exe.
        self.root.report_callback_exception = (
            lambda et, ev, tb: log.error("UI callback failed", exc_info=(et, ev, tb)))
        try:  # default icon for every FlowClone window (the Hub included)
            from . import paths
            self.root.iconbitmap(default=str(paths.resource("assets/flowclone.ico")))
        except Exception:
            log.debug("window icon not set", exc_info=True)

        self.pill = tk.Toplevel(self.root)
        self.pill.overrideredirect(True)
        self.pill.attributes("-topmost", True)
        self.pill.attributes("-transparentcolor", _TRANSPARENT)
        self.pill.configure(bg=_TRANSPARENT)
        self.canvas = tk.Canvas(self.pill, width=360, height=40,
                                bg=_TRANSPARENT, highlightthickness=0)
        self.canvas.pack()
        self._rect = self._rounded_rect(0, 0, 360, 40, 19, fill=_BG)
        self._text = self.canvas.create_text(180, 20, text="", fill="#ffffff",
                                             font=("Segoe UI", 10))
        self.pill.withdraw()

        self._ready.set()
        self._poll()
        self._animate()
        self.root.mainloop()

    def _rounded_rect(self, x1, y1, x2, y2, r, **kw):
        pts = [x1+r,y1, x2-r,y1, x2,y1, x2,y1+r, x2,y2-r, x2,y2, x2-r,y2,
               x1+r,y2, x1,y2, x1,y2-r, x1,y1+r, x1,y1]
        return self.canvas.create_polygon(pts, smooth=True, **kw)

    def _place(self):
        sw = self.pill.winfo_screenwidth()
        sh = self.pill.winfo_screenheight()
        self.pill.geometry(f"360x40+{(sw - 360) // 2}+{sh - 116}")

    def _apply_state(self, state, message, flash_ms, seq):
        # A flash scheduled for an earlier message must not hide this one.
        if self._flash_id is not None:
            self.pill.after_cancel(self._flash_id)
            self._flash_id = None
        if state in _ALWAYS_SHOWN:
            self._sticky = (state, message)
        elif not flash_ms:
            self._sticky = None
        if flash_ms:
            # Scheduled even when the pill is off, so the state always returns.
            self._flash_seq = seq
            self._flash_id = self.pill.after(flash_ms, self._flash_done)
        self._show(state, message)

    def _show(self, state, message):
        self._state = state
        if state == "hidden" or (not self.show_pill and state not in _ALWAYS_SHOWN):
            self.pill.withdraw()
            return
        color, default_text = _STATES.get(state, ("#f0a020", ""))
        self.canvas.itemconfigure(self._text, text=self._fit(message or default_text), fill=color)
        self.pill.deiconify()
        self.pill.attributes("-topmost", True)
        self._place()

    def _flash_done(self):
        self._flash_id = None
        state, message = self._sticky or ("hidden", None)
        with self._lock:
            if self._seq == self._flash_seq:  # nothing newer was requested meanwhile
                self._requested = state
        self._show(state, message)

    def _fit(self, text: str) -> str:
        """Ellipsize text that would not fit on the pill."""
        try:
            import tkinter.font as tkfont
            font = tkfont.Font(font=self.canvas.itemcget(self._text, "font"))
            limit = _PILL_W - 24
            if font.measure(text) <= limit:
                return text
            while text and font.measure(text + "…") > limit:
                text = text[:-1]
            return text + "…"
        except Exception:
            return text[:48]

    def _poll(self):
        try:
            while True:
                kind, a, b, c, d = self._q.get_nowait()
                if kind == "state":
                    try:
                        self._apply_state(a, b, c, d)
                    except Exception:
                        log.exception("pill update failed")
                elif kind == "call":
                    try:
                        a()
                    except Exception as e:
                        log.exception("scheduled UI call failed: %s", e)
        except queue.Empty:
            pass
        self.root.after(60, self._poll)

    def _animate(self):
        # pulse the red dot while recording
        if self._state in ("recording", "hands_free"):
            self._pulse_on = not self._pulse_on
            color = "#e5484d" if self._pulse_on else "#8a2b2e"
            self.canvas.itemconfigure(self._text, fill=color)
        self.root.after(450, self._animate)
