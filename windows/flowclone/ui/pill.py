"""The status pill near the bottom of the screen: live waveform while you talk, a shimmer while
transcribing, download progress, errors.

It must never take keyboard focus: the transcript is pasted into whatever window is in front
while the pill is visible. So it is a tool window that does not accept focus, ignores the mouse
and is shown without activation; it is created once and only shown/hidden afterwards.
"""
import logging
import math
import re
import sys
from collections import deque

from PySide6.QtCore import QEasingCurve, QPropertyAnimation, QRectF, Qt, QTimer
from PySide6.QtGui import QColor, QFont, QFontMetrics, QGuiApplication, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import QWidget

from ..pill_state import PillController, PillModel

log = logging.getLogger("flowclone")

W, H = 320, 46          # capsule
MARGIN = 10             # room for the soft shadow
BARS = 22

_DEFAULT_TEXT = {
    "recording": "Listening",
    "hands_free": "Hands-free — hotkey or Esc to stop",
    "command": "Command — say what to do",
    "processing": "Transcribing",
    "loading": "Loading speech model",
    "downloading": "Downloading speech model",
    "model_error": "Speech model problem",
}
_COLORS = {
    "recording": "#E5484D", "hands_free": "#E5484D", "command": "#9B7BFF",
    "processing": "#F0A020", "loading": "#4C6FFF", "downloading": "#4C6FFF",
    "model_error": "#E5484D", "info": "#4C6FFF",
}
_PCT = re.compile(r"(\d{1,3})%")


class Pill(QWidget):
    def __init__(self, controller: PillController, bridge):
        super().__init__(None, Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
                         | Qt.WindowDoesNotAcceptFocus | Qt.WindowTransparentForInput)
        self.ctl = controller
        self.model = PillModel()
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self.setFocusPolicy(Qt.NoFocus)
        self.setFixedSize(W + 2 * MARGIN, H + 2 * MARGIN)
        self.setWindowTitle("FlowClone status")

        self._state = "hidden"
        self._text = ""
        self._levels = deque([0.0] * BARS, maxlen=BARS)
        self._target_level = 0.0
        self._phase = 0.0
        self._flash = QTimer(self)
        self._flash.setSingleShot(True)
        self._flash.timeout.connect(self._flash_done)
        self._tick = QTimer(self)
        self._tick.setInterval(33)
        self._tick.timeout.connect(self._animate)
        self._fade = QPropertyAnimation(self, b"windowOpacity", self)
        self._fade.setDuration(120)
        self._fade.setEasingCurve(QEasingCurve.OutCubic)
        self._fade.finished.connect(self._fade_finished)
        self._hiding = False
        bridge.pill.connect(self._on_event)

    # ---------- events from PillController (UI thread) ----------

    def _on_event(self, event):
        kind = event[0]
        if kind == "state":
            _, state, message, flash_ms, seq = event
            d = self.model.on_request(state, message, flash_ms, seq, self.ctl.show_pill)
            self._flash.stop()
            if flash_ms:
                self._flash.start(flash_ms)
            self._display(d)
        elif kind == "call":
            try:
                event[1]()
            except Exception:
                log.exception("scheduled UI call failed")
        elif kind == "level":
            self._target_level = event[1]

    def _flash_done(self):
        d = self.model.on_flash_done(self.ctl.show_pill)
        self.ctl.flash_finished(self.model.flash_seq, d.state)
        self._display(d)

    def _display(self, d):
        if d.state != self._state:
            self._levels = deque([0.0] * BARS, maxlen=BARS)
        self._state = d.state
        self._text = d.message or _DEFAULT_TEXT.get(d.state, "")
        if d.visible:
            self._show()
        else:
            self._hide()
        self.update()

    # ---------- show / hide without ever activating ----------

    def _show(self):
        if not self.isVisible() or self._hiding:
            self._hiding = False
            self._place()
            if not self.isVisible():
                self.setWindowOpacity(0.0)
                self.show()
            self._topmost()
            self._fade.stop()
            self._fade.setStartValue(self.windowOpacity())
            self._fade.setEndValue(1.0)
            self._fade.start()
        self._sync_timer()

    def _sync_timer(self):
        # Only states that move need the 30 fps timer; static ones are painted once.
        moving = self._state in ("recording", "hands_free", "command", "processing", "loading")
        if moving and not self._tick.isActive():
            self._tick.start()
        elif not moving and self._tick.isActive():
            self._tick.stop()

    def _hide(self):
        if self.isVisible() and not self._hiding:
            self._hiding = True
            self._fade.stop()
            self._fade.setStartValue(self.windowOpacity())
            self._fade.setEndValue(0.0)
            self._fade.start()

    def _fade_finished(self):
        if self._hiding:
            self._hiding = False
            self.hide()
            self._tick.stop()

    def _topmost(self):
        if sys.platform != "win32":
            return
        try:
            import ctypes
            # HWND_TOPMOST, SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE|SWP_SHOWWINDOW
            ctypes.windll.user32.SetWindowPos(int(self.winId()), -1, 0, 0, 0, 0,
                                              0x0002 | 0x0001 | 0x0010 | 0x0040)
        except Exception:
            pass

    def _place(self):
        screen = _screen_of_foreground_window() or QGuiApplication.primaryScreen()
        if screen is None:
            return
        if self.screen() is not screen:
            self.setScreen(screen)
        g = screen.availableGeometry()
        x = g.x() + (g.width() - self.width()) // 2
        y = g.y() + g.height() - self.height() - 86
        self.move(x, y)

    # ---------- painting ----------

    def _animate(self):
        self._phase = (self._phase + 0.12) % (2 * math.pi)
        if self._state in ("recording", "hands_free", "command"):
            level = min(1.0, (max(0.0, self._target_level) * 9.0) ** 0.7)
            self._levels.append(level)
            self._target_level *= 0.6
        self.update()

    def paintEvent(self, _event):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(MARGIN, MARGIN, W, H)
        # soft shadow
        for i, alpha in enumerate((28, 18, 10)):
            grow = 2 + i * 2
            path = QPainterPath()
            path.addRoundedRect(rect.adjusted(-grow, -grow + 2, grow, grow + 2),
                                H / 2 + grow, H / 2 + grow)
            p.fillPath(path, QColor(0, 0, 0, alpha))
        path = QPainterPath()
        path.addRoundedRect(rect, H / 2, H / 2)
        p.fillPath(path, QColor("#1A1A1F"))
        p.setPen(QPen(QColor("#34343D"), 1))
        p.drawPath(path)

        color = QColor(_COLORS.get(self._state, "#A0A0AB"))
        left = rect.left() + 18
        cy = rect.center().y()

        if self._state in ("recording", "hands_free", "command"):
            pulse = 0.55 + 0.45 * (0.5 + 0.5 * math.sin(self._phase * 1.6))
            dot = QColor(color)
            dot.setAlphaF(pulse)
            p.setPen(Qt.NoPen)
            p.setBrush(dot)
            p.drawEllipse(QRectF(left - 5, cy - 5, 10, 10))
            # waveform on the right
            bar_w, gap = 3.0, 2.5
            wave_w = BARS * (bar_w + gap)
            x0 = rect.right() - 18 - wave_w
            p.setBrush(QColor("#F2F2F5"))
            for i, lv in enumerate(self._levels):
                h = 3 + lv * (H - 18)
                p.drawRoundedRect(QRectF(x0 + i * (bar_w + gap), cy - h / 2, bar_w, h), 1.5, 1.5)
            text_right = x0 - 10
        elif self._state in ("processing", "loading"):
            p.setPen(Qt.NoPen)
            for i in range(3):
                a = 0.3 + 0.7 * (0.5 + 0.5 * math.sin(self._phase * 2 - i * 0.9))
                c = QColor(color)
                c.setAlphaF(a)
                p.setBrush(c)
                p.drawEllipse(QRectF(left - 6 + i * 8, cy - 3, 6, 6))
            left += 14
            text_right = rect.right() - 18
        else:
            p.setPen(Qt.NoPen)
            p.setBrush(color)
            p.drawEllipse(QRectF(left - 4, cy - 4, 8, 8))
            text_right = rect.right() - 18

        if self._state == "downloading":
            m = _PCT.search(self._text or "")
            pct = min(100, int(m.group(1))) / 100 if m else 0.0
            track = QRectF(rect.left() + 22, rect.bottom() - 7, W - 44, 3)
            p.setBrush(QColor("#2C2C34"))
            p.drawRoundedRect(track, 1.5, 1.5)
            p.setBrush(color)
            p.drawRoundedRect(QRectF(track.left(), track.top(), track.width() * pct, 3), 1.5, 1.5)

        font = QFont(self.font())
        font.setPointSizeF(10)
        font.setWeight(QFont.DemiBold)
        p.setFont(font)
        p.setPen(QColor("#F2F2F5"))
        text_left = left + 14
        avail = int(text_right - text_left)
        shown = QFontMetrics(font).elidedText(self._text, Qt.ElideRight, max(20, avail))
        p.drawText(QRectF(text_left, rect.top(), avail, H - (6 if self._state == "downloading" else 0)),
                   Qt.AlignVCenter | Qt.AlignLeft, shown)
        p.end()


def _screen_of_foreground_window():
    """The QScreen showing the window the user is typing into (falls back to the cursor's)."""
    if sys.platform == "win32":
        try:
            import ctypes
            from ctypes import wintypes

            class MONITORINFO(ctypes.Structure):
                _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                            ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]
            user32 = ctypes.windll.user32
            mon = user32.MonitorFromWindow(user32.GetForegroundWindow(), 2)  # NEAREST
            info = MONITORINFO()
            info.cbSize = ctypes.sizeof(MONITORINFO)
            if mon and user32.GetMonitorInfoW(mon, ctypes.byref(info)):
                r = info.rcMonitor
                cx, cy = (r.left + r.right) // 2, (r.top + r.bottom) // 2
                for s in QGuiApplication.screens():
                    g, dpr = s.geometry(), s.devicePixelRatio()
                    if g.x() <= cx < g.x() + g.width() * dpr and g.y() <= cy < g.y() + g.height() * dpr:
                        return s
        except Exception:
            pass
    from PySide6.QtGui import QCursor
    return QGuiApplication.screenAt(QCursor.pos())
