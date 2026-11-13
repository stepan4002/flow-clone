"""The 'Flow Bar' — a rounded always-on-top status pill near the bottom of the
screen — plus the shared tkinter thread that also hosts the Hub window.

All tkinter work happens on this module's thread. Other threads use set_state()
for the pill and schedule() to run UI callables (e.g. opening the Hub).
"""
import queue
import threading
import tkinter as tk

_STATES = {
    "recording": ("#e5484d", "●  Listening…"),
    "hands_free": ("#e5484d", "●  Hands-free — hotkey or Esc to stop"),
    "processing": ("#f0a020", "…  Transcribing"),
    "loading": ("#4a90d9", "⏳  Loading speech model…"),
}
_BG = "#1c1c1e"
_TRANSPARENT = "#010203"  # color-keyed to transparent for rounded corners


class Overlay:
    def __init__(self, show_pill: bool = True):
        self.show_pill = show_pill
        self._q: queue.Queue = queue.Queue()
        self._pulse_on = False
        self._state = "hidden"
        self.root: tk.Tk | None = None
        self._ready = threading.Event()
        threading.Thread(target=self._run, daemon=True, name="ui").start()
        self._ready.wait(timeout=10)

    # ---- thread-safe API ----

    def set_state(self, state: str, message: str | None = None, flash_ms: int = 0):
        self._q.put(("state", state, message, flash_ms))

    def schedule(self, fn):
        """Run fn() on the tkinter thread (used by tray to open the Hub)."""
        self._q.put(("call", fn, None, None))

    # ---- tk thread ----

    def _run(self):
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title("FlowClone")

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

    def _apply_state(self, state, message, flash_ms):
        self._state = state
        if state == "hidden" or not self.show_pill:
            self.pill.withdraw()
            return
        color, default_text = _STATES.get(state, ("#ffffff", state))
        self.canvas.itemconfigure(self._text, text=message or default_text, fill=color)
        self.pill.deiconify()
        self.pill.attributes("-topmost", True)
        self._place()
        if flash_ms:
            self.pill.after(flash_ms, self.pill.withdraw)

    def _poll(self):
        try:
            while True:
                kind, a, b, c = self._q.get_nowait()
                if kind == "state":
                    self._apply_state(a, b, c)
                elif kind == "call":
                    try:
                        a()
                    except Exception as e:
                        print(f"[ui] scheduled call failed: {e}")
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
