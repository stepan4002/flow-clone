"""First-run setup: welcome, microphone check, a first dictation, and where to find FlowClone.

Non-modal and shown after the hotkeys and the model download have started, so "Try it" works
as soon as the speech model is ready. The microphone meter uses its own audio stream (not the
dictation recorder) and the test dictation is not counted in history or stats.
"""
import logging

from PySide6.QtCore import QObject, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QIcon, QPainter
from PySide6.QtWidgets import (QComboBox, QHBoxLayout, QPlainTextEdit, QStackedWidget,
                               QVBoxLayout, QWidget)

from .. import paths
from .pages.settings import input_devices
from .widgets import Card, button, keycaps, label
from .winchrome import apply_titlebar, force_foreground

log = logging.getLogger("flowclone")


class _Level(QObject):
    changed = Signal(float)


class LevelMeter(QWidget):
    def __init__(self, theme):
        super().__init__()
        self.theme = theme
        self.value = 0.0
        self.setMinimumHeight(14)
        self.setAccessibleName("Microphone level")

    def set_level(self, rms: float):
        target = min(1.0, (max(0.0, rms) * 9.0) ** 0.7)
        self.value = max(target, self.value * 0.85)
        self.update()

    def paintEvent(self, _e):
        t = self.theme.tokens
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(0, 0, self.width(), self.height())
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(t["surface3"]))
        p.drawRoundedRect(r, 7, 7)
        p.setBrush(QColor(t["success"] if self.value > 0.15 else t["accent"]))
        p.drawRoundedRect(QRectF(0, 0, max(14.0, r.width() * self.value), r.height()), 7, 7)
        p.end()


class Onboarding(QWidget):
    STEPS = 4

    def __init__(self, app):
        super().__init__(None, Qt.Window)
        self.app = app
        t = app.theme
        self.setWindowTitle("Welcome to FlowClone")
        self.setWindowIcon(QIcon(str(paths.resource("assets/flowclone.ico"))))
        self.setObjectName("Root")
        self.setMinimumSize(620, 520)
        self.resize(700, 580)
        self._stream = None
        self._level = _Level()
        self._finished = False

        lay = QVBoxLayout(self)
        lay.setContentsMargins(32, 28, 32, 20)
        lay.setSpacing(14)
        self.dots = label("", "faint")
        lay.addWidget(self.dots)
        self.stack = QStackedWidget()
        lay.addWidget(self.stack, 1)

        # 1 welcome
        w1 = self._page("Speak instead of type — anywhere",
                        "Hold a hotkey, talk, let go. FlowClone writes what you said into the app "
                        "you're using: email, chat, documents, code, the browser.")
        priv = Card(accent=True)
        priv.body.addWidget(label("Private by design: speech recognition runs on this computer. "
                                  "Your voice is never uploaded. No account, no tracking.",
                                  wrap=True))
        w1.layout().addWidget(priv)
        w1.layout().addStretch()
        self.stack.addWidget(w1)

        # 2 microphone
        w2 = self._page("Check your microphone", "Say something — the bar should move.")
        self.mic = QComboBox()
        self.mic.setAccessibleName("Microphone")
        for idx, name in input_devices():
            self.mic.addItem(name, idx)
        i = self.mic.findData(app.config["audio"].get("device"))
        self.mic.setCurrentIndex(max(0, i))
        self.mic.currentIndexChanged.connect(self._mic_changed)
        w2.layout().addWidget(self.mic)
        self.meter = LevelMeter(t)
        w2.layout().addWidget(self.meter)
        self.mic_hint = label("", "faint", wrap=True)
        w2.layout().addWidget(self.mic_hint)
        w2.layout().addStretch()
        self._level.changed.connect(self.meter.set_level)
        self.stack.addWidget(w2)

        # 3 try it
        w3 = self._page("Try it", "")
        r = QHBoxLayout()
        r.addWidget(label("Click the box, hold", "h3"))
        r.addWidget(keycaps(app.config["hotkeys"]["push_to_talk"]))
        r.addWidget(label("and say: \"Hello, this is my first dictation.\"", "h3"))
        r.addStretch()
        w3.layout().addLayout(r)
        self.try_box = QPlainTextEdit()
        self.try_box.setPlaceholderText("Your words will appear here…")
        self.try_box.setAccessibleName("Try dictation here")
        self.try_box.textChanged.connect(self._tried)
        w3.layout().addWidget(self.try_box, 1)
        self.try_hint = label("", "muted", wrap=True)
        w3.layout().addWidget(self.try_hint)
        self.retry = button("Retry the download", None, "refresh", t)
        self.retry.clicked.connect(app.retry_model_load)
        self.retry.hide()
        w3.layout().addWidget(self.retry, 0, Qt.AlignLeft)
        self.stack.addWidget(w3)

        # 4 tips
        w4 = self._page("You're all set", "")
        for title, text in (
                ("Find FlowClone in the tray",
                 "Its icon sits by the clock — on Windows 11 it may be hidden under ^. Drag it "
                 "onto the taskbar to keep it visible. Click it to open this app."),
                ("Hands-free", "Double-tap the hotkey to keep recording without holding; tap "
                               "again (or Esc) to stop."),
                ("Command Mode", "Select text, hold the hotkey + Shift and say what to do: "
                                 "\"make this shorter\", \"translate to Spanish\". Needs AI "
                                 "(Settings → AI)."),
                ("Make it yours", "Add names and jargon to the Dictionary, set Styles per app, "
                                  "and create Snippets for text you type often.")):
            c = Card(title)
            c.body.addWidget(label(text, "muted", wrap=True))
            w4.layout().addWidget(c)
        w4.layout().addStretch()
        self.stack.addWidget(w4)

        # footer
        foot = QHBoxLayout()
        self.model = label("", "chip-warn")
        foot.addWidget(self.model)
        foot.addStretch()
        self.skip = button("Skip", "ghost")
        self.skip.clicked.connect(self._next)
        self.back = button("Back", None)
        self.back.clicked.connect(lambda: self._go(self.stack.currentIndex() - 1))
        self.next = button("Next", "primary")
        self.next.clicked.connect(self._next)
        for b in (self.skip, self.back, self.next):
            foot.addWidget(b)
        lay.addLayout(foot)

        app.bridge.model_status.connect(self._model_status)
        self._model_status(*app.model_status)
        app.theme.changed.connect(self._theme_changed)
        self._go(0)

    def _theme_changed(self):
        from .widgets import restyle_tree
        restyle_tree(self, self.app.theme)
        apply_titlebar(self, self.app.theme.dark, self.app.theme.tokens["bg"])

    def _page(self, title, subtitle):
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(12)
        v.addWidget(label(title, "h1"))
        if subtitle:
            v.addWidget(label(subtitle, "muted", wrap=True))
        return w

    # ---------- navigation ----------

    def _go(self, i):
        i = max(0, min(self.STEPS - 1, i))
        prev = self.stack.currentIndex()
        if prev == 1 and i != 1:
            self._stop_meter()
        if prev == 2 and i != 2:
            self.app.test_mode = False
        self.stack.setCurrentIndex(i)
        self.dots.setText("   ".join("●" if k == i else "○" for k in range(self.STEPS)))
        self.back.setVisible(i > 0)
        self.skip.setVisible(i == 2)
        self.next.setText("Start using FlowClone" if i == self.STEPS - 1 else
                          "Get started" if i == 0 else "Next")
        if i == 1:
            self._start_meter()
        if i == 2:
            self.app.test_mode = True
            self._update_try()
            self.try_box.setFocus()

    def _next(self):
        if self.stack.currentIndex() == self.STEPS - 1:
            self._finish()
        else:
            self._go(self.stack.currentIndex() + 1)

    def _finish(self):
        self._finished = True
        self.close()

    def closeEvent(self, e):  # noqa: N802
        self._stop_meter()
        self.app.test_mode = False
        if self._finished or self.stack.currentIndex() > 0:
            self.app.save_settings(lambda c: c["ui"].__setitem__("onboarding_done", True),
                                   live=False)
            if self._finished:
                self.app.open_hub("home")
        super().closeEvent(e)

    def showEvent(self, e):  # noqa: N802
        super().showEvent(e)
        screen = self.screen().availableGeometry()
        self.move(screen.center().x() - self.width() // 2, screen.center().y() - self.height() // 2)
        apply_titlebar(self, self.app.theme.dark, self.app.theme.tokens["bg"])
        force_foreground(self)

    # ---------- microphone ----------

    def _start_meter(self):
        self._stop_meter()
        try:
            import numpy as np
            import sounddevice as sd

            def cb(indata, frames, t, status):
                block = indata[:, 0]
                self._level.changed.emit(float(np.sqrt(np.mean(block * block))) if block.size else 0.0)
            stream = sd.InputStream(samplerate=16000, channels=1, dtype="float32",
                                    device=self.mic.currentData(), callback=cb)
            try:
                stream.start()
            except Exception:
                stream.close()
                raise
            self._stream = stream
            self.mic_hint.setText("")
        except Exception as e:
            self._stream = None
            self.mic_hint.setText(f"Couldn't open this microphone ({type(e).__name__}). Check "
                                  "Windows Settings → Privacy → Microphone.")

    def _stop_meter(self):
        s, self._stream = self._stream, None
        if s is not None:
            try:
                s.stop()
                s.close()
            except Exception:
                pass

    def _mic_changed(self, _):
        dev = self.mic.currentData()
        self.app.save_settings(lambda c: c["audio"].__setitem__("device", dev))
        if self.stack.currentIndex() == 1:
            self._start_meter()

    # ---------- try it ----------

    def _model_status(self, state, pct):
        text, role = {"ready": ("● Speech model ready", "chip-ok"),
                      "error": ("● Speech model download failed", "chip-err"),
                      "downloading": (f"● Downloading speech model {pct}%", "chip-warn"),
                      "loading": ("● Loading speech model…", "chip-warn")}.get(state, (state, "chip"))
        self.model.setText(text)
        self.model.setProperty("role", role)
        self.model.style().unpolish(self.model)
        self.model.style().polish(self.model)
        self._update_try()

    def _update_try(self):
        state, pct = self.app.model_status
        ready = state == "ready"
        self.try_box.setEnabled(ready)
        self.retry.setVisible(state == "error")
        if ready:
            if not self.try_box.toPlainText().strip():
                self.try_hint.setText("Ready when you are.")
        elif state == "error":
            self.try_hint.setText("The speech model couldn't be downloaded. Check your internet "
                                  "connection and retry — or skip and try later.")
        else:
            self.try_hint.setText(f"Getting the speech model ready ({pct}%) — this happens once. "
                                  "You can skip and try later.")

    def _tried(self):
        if self.try_box.toPlainText().strip():
            self.try_hint.setText("That's it! It works in every app.")
            self.next.setText("Next")
