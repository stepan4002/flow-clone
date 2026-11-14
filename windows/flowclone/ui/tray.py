"""The notification-area (tray) icon and its menu.

set_active / set_tooltip / refresh_menu may be called from any thread: they only emit Bridge
signals. Menu actions that block (pasting, starting a recording) run on a worker thread, never on
the UI thread.
"""
import os
import threading

from PySide6.QtGui import QAction, QIcon
from PySide6.QtWidgets import QMenu, QSystemTrayIcon

from .. import paths
from ..config import APP_DIR


def _bg(fn):
    return lambda *_: threading.Thread(target=fn, daemon=True).start()


class TrayIcon:
    def __init__(self, bridge):
        self.bridge = bridge
        self.app = None
        self._icons = {False: QIcon(str(paths.resource("assets/flowclone.ico"))),
                       True: QIcon(str(paths.resource("assets/flowclone-active.ico")))}
        self.icon = QSystemTrayIcon(self._icons[False])
        self.icon.setToolTip("FlowClone")
        self.menu = QMenu()  # kept as an attribute: a collected QMenu would vanish
        self.icon.setContextMenu(self.menu)
        self.icon.activated.connect(self._activated)
        bridge.tray_active.connect(lambda on: self.icon.setIcon(self._icons[bool(on)]))
        bridge.tray_tooltip.connect(lambda t: self.icon.setToolTip(t[:127]))
        bridge.tray_refresh.connect(self._refresh)
        bridge.notify.connect(lambda title, msg: self.icon.showMessage(
            title, msg, self._icons[False], 6000))

    def set_app(self, app):
        self.app = app
        m = self.menu
        m.clear()
        self._open = QAction("Open FlowClone", m)
        self._open.triggered.connect(lambda: app.open_hub())
        f = self._open.font()
        f.setBold(True)
        self._open.setFont(f)
        m.addAction(self._open)
        m.addSeparator()
        a = QAction("Toggle hands-free dictation", m)
        a.triggered.connect(_bg(app.toggle_hands_free))
        m.addAction(a)
        a = QAction("Paste last transcript", m)
        a.triggered.connect(_bg(app.paste_last))
        m.addAction(a)
        m.addSeparator()
        self._retry = QAction("Retry speech model download", m)
        self._retry.triggered.connect(lambda: app.retry_model_load())
        m.addAction(self._retry)
        a = QAction("Open settings folder", m)
        a.triggered.connect(lambda: os.startfile(str(APP_DIR)))
        m.addAction(a)
        m.addSeparator()
        a = QAction("Quit FlowClone", m)
        a.triggered.connect(lambda: app.quit())
        m.addAction(a)
        self._refresh()

    def show(self):
        self.icon.show()

    def _activated(self, reason):
        if self.app and reason in (QSystemTrayIcon.Trigger, QSystemTrayIcon.DoubleClick):
            self.app.open_hub()

    def _refresh(self):
        if self.app is not None:
            self._retry.setEnabled(bool(self.app.model_failed))

    # ---------- thread-safe API used by FlowApp ----------

    def set_active(self, active: bool):
        self.bridge.tray_active.emit(bool(active))

    def set_tooltip(self, text: str):
        self.bridge.tray_tooltip.emit(text)

    def refresh_menu(self):
        self.bridge.tray_refresh.emit()

    def start(self):  # compatibility with the old tray API
        self.bridge.call.emit(self.show)

    def stop(self):
        self.bridge.call.emit(self.icon.hide)
