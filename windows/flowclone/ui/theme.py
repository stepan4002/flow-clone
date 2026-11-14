"""FlowClone design tokens and the Qt stylesheet (dark + light).

Widgets opt into styles with dynamic properties, e.g. QFrame[card="true"], QLabel[role="h1"],
QPushButton[kind="primary"]. The same tokens are used by the Android app (Phase B).
"""
import logging
import sys

from PySide6.QtCore import QObject, Signal
from PySide6.QtGui import QColor, QFont, QGuiApplication, QPalette
from PySide6.QtCore import Qt

log = logging.getLogger("flowclone")

DARK = {
    "bg": "#111114", "surface": "#1A1A1F", "surface2": "#232329", "surface3": "#2C2C34",
    "border": "#2E2E36", "text": "#F2F2F5", "text2": "#A0A0AB", "text3": "#6E6E7A",
    "accent": "#4C6FFF", "accent_hover": "#6A87FF", "accent_soft": "#232B4D",
    "recording": "#E5484D", "warning": "#F0A020", "success": "#2EB67D",
    "pill": "#1A1A1F", "pill_border": "#34343D",
}
LIGHT = {
    "bg": "#F6F6F8", "surface": "#FFFFFF", "surface2": "#F0F0F4", "surface3": "#E6E6EC",
    "border": "#E2E2E8", "text": "#16161A", "text2": "#5E5E6A", "text3": "#8E8E99",
    "accent": "#3D5AFE", "accent_hover": "#5872FF", "accent_soft": "#E6EAFF",
    "recording": "#D93036", "warning": "#C77C00", "success": "#1F9D66",
    "pill": "#1A1A1F", "pill_border": "#34343D",   # the pill stays dark on any wallpaper
}
FONT_FAMILY = "Segoe UI Variable Text"
FONT_FALLBACK = "Segoe UI"


def _qss(t: dict) -> str:
    return f"""
* {{ color: {t['text']}; }}
QWidget#Root, QMainWindow, QDialog {{ background: {t['bg']}; }}
QWidget#Sidebar {{ background: {t['surface']}; border-right: 1px solid {t['border']}; }}
QWidget#Page {{ background: {t['bg']}; }}
QScrollArea, QScrollArea > QWidget > QWidget {{ background: transparent; border: none; }}

QLabel {{ background: transparent; }}
QLabel[role="brand"] {{ font-size: 15pt; font-weight: 600; }}
QLabel[role="h1"] {{ font-size: 20pt; font-weight: 600; }}
QLabel[role="h2"] {{ font-size: 13pt; font-weight: 600; }}
QLabel[role="h3"] {{ font-size: 11pt; font-weight: 600; }}
QLabel[role="muted"] {{ color: {t['text2']}; }}
QLabel[role="faint"] {{ color: {t['text3']}; font-size: 9pt; }}
QLabel[role="stat"] {{ font-size: 22pt; font-weight: 600; }}
QLabel[role="kbd"] {{ background: {t['surface3']}; border: 1px solid {t['border']};
    border-bottom-width: 2px; border-radius: 6px; padding: 2px 8px; font-weight: 600; }}
QLabel[role="chip"] {{ background: {t['surface2']}; border-radius: 10px; padding: 3px 10px;
    color: {t['text2']}; }}
QLabel[role="chip-ok"] {{ background: {t['surface2']}; border-radius: 10px; padding: 3px 10px;
    color: {t['success']}; }}
QLabel[role="chip-warn"] {{ background: {t['surface2']}; border-radius: 10px; padding: 3px 10px;
    color: {t['warning']}; }}
QLabel[role="chip-err"] {{ background: {t['surface2']}; border-radius: 10px; padding: 3px 10px;
    color: {t['recording']}; }}

QFrame[card="true"] {{ background: {t['surface']}; border: 1px solid {t['border']};
    border-radius: 12px; }}
QFrame[card="accent"] {{ background: {t['accent_soft']}; border: 1px solid {t['accent']};
    border-radius: 12px; }}
QFrame[sep="true"] {{ background: {t['border']}; max-height: 1px; min-height: 1px; border: none; }}

QPushButton {{ background: {t['surface2']}; border: 1px solid {t['border']}; border-radius: 8px;
    padding: 6px 14px; }}
QPushButton:hover {{ background: {t['surface3']}; }}
QPushButton:pressed {{ background: {t['border']}; }}
QPushButton:disabled {{ color: {t['text3']}; }}
QPushButton:focus {{ border-color: {t['accent']}; }}
QPushButton[kind="primary"] {{ background: {t['accent']}; border-color: {t['accent']};
    color: #FFFFFF; font-weight: 600; }}
QPushButton[kind="primary"]:hover {{ background: {t['accent_hover']}; }}
QPushButton[kind="ghost"] {{ background: transparent; border-color: transparent; }}
QPushButton[kind="ghost"]:hover {{ background: {t['surface2']}; }}
QPushButton[kind="danger"] {{ color: {t['recording']}; }}
QPushButton[nav="true"] {{ background: transparent; border: none; border-radius: 8px;
    padding: 8px 12px; text-align: left; color: {t['text2']}; font-size: 10.5pt; }}
QPushButton[nav="true"]:hover {{ background: {t['surface2']}; color: {t['text']}; }}
QPushButton[nav="true"]:checked {{ background: {t['accent_soft']}; color: {t['text']};
    font-weight: 600; }}
QPushButton[seg="true"] {{ background: transparent; border: none; border-radius: 6px;
    padding: 5px 12px; color: {t['text2']}; }}
QPushButton[seg="true"]:checked {{ background: {t['surface']}; color: {t['text']};
    font-weight: 600; }}
QPushButton[chip="true"] {{ background: transparent; border: 1px solid {t['border']};
    border-radius: 13px; padding: 3px 12px; }}
QPushButton[chip="true"]:hover {{ background: {t['surface2']}; border-color: {t['text3']}; }}
QFrame[segbar="true"] {{ background: {t['surface2']}; border-radius: 8px; }}

QLineEdit, QPlainTextEdit, QTextEdit, QSpinBox, QComboBox {{ background: {t['surface2']};
    border: 1px solid {t['border']}; border-radius: 8px; padding: 6px 10px;
    selection-background-color: {t['accent']}; selection-color: #FFFFFF; }}
QLineEdit:focus, QPlainTextEdit:focus, QTextEdit:focus, QSpinBox:focus, QComboBox:focus {{
    border-color: {t['accent']}; }}
QComboBox::drop-down {{ border: none; width: 22px; }}
QComboBox QAbstractItemView {{ background: {t['surface']}; border: 1px solid {t['border']};
    selection-background-color: {t['accent_soft']}; outline: none; padding: 4px; }}
QSpinBox::up-button, QSpinBox::down-button {{ width: 0; border: none; }}

QTableView, QListWidget, QTreeView {{ background: {t['surface']}; border: 1px solid {t['border']};
    border-radius: 12px; gridline-color: transparent; outline: none;
    selection-background-color: {t['accent_soft']}; selection-color: {t['text']}; }}
QTableView::item, QListWidget::item {{ padding: 6px 8px; border: none; }}
QHeaderView::section {{ background: {t['surface']}; color: {t['text2']}; border: none;
    border-bottom: 1px solid {t['border']}; padding: 8px; font-weight: 600; }}

QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: {t['surface3']}; border-radius: 4px; min-height: 30px; }}
QScrollBar::handle:vertical:hover {{ background: {t['text3']}; }}
QScrollBar:horizontal {{ background: transparent; height: 10px; margin: 2px; }}
QScrollBar::handle:horizontal {{ background: {t['surface3']}; border-radius: 4px; min-width: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line, QScrollBar::add-page, QScrollBar::sub-page {{
    background: none; border: none; width: 0; height: 0; }}

QToolTip {{ background: {t['surface3']}; color: {t['text']}; border: 1px solid {t['border']};
    border-radius: 6px; padding: 4px 8px; }}
QMenu {{ background: {t['surface']}; border: 1px solid {t['border']}; border-radius: 8px;
    padding: 4px; }}
QMenu::item {{ padding: 6px 24px 6px 12px; border-radius: 6px; }}
QMenu::item:selected {{ background: {t['accent_soft']}; }}
QMenu::item:disabled {{ color: {t['text3']}; }}
QMenu::separator {{ height: 1px; background: {t['border']}; margin: 4px 8px; }}
QMessageBox {{ background: {t['bg']}; }}
"""


def high_contrast() -> bool:
    if sys.platform != "win32":
        return False
    try:
        import ctypes
        from ctypes import wintypes

        class HIGHCONTRAST(ctypes.Structure):
            _fields_ = [("cbSize", wintypes.UINT), ("dwFlags", wintypes.DWORD),
                        ("lpszDefaultScheme", wintypes.LPWSTR)]
        hc = HIGHCONTRAST()
        hc.cbSize = ctypes.sizeof(HIGHCONTRAST)
        ctypes.windll.user32.SystemParametersInfoW(0x0042, hc.cbSize, ctypes.byref(hc), 0)
        return bool(hc.dwFlags & 0x1)
    except Exception:
        return False


class Theme(QObject):
    """Current tokens; emits changed() when the user or Windows switches dark/light."""
    changed = Signal()

    def __init__(self, app, mode: str = "system"):
        super().__init__()
        self.app = app
        self.mode = mode
        self.tokens = DARK
        self.dark = True
        app.setStyle("Fusion")
        font = QFont(FONT_FAMILY)
        if not font.exactMatch():
            font = QFont(FONT_FALLBACK)
        font.setPointSizeF(10)
        app.setFont(font)
        hints = QGuiApplication.styleHints()
        try:
            hints.colorSchemeChanged.connect(lambda *_: self.apply())
        except AttributeError:
            pass
        self.apply()

    def set_mode(self, mode: str):
        self.mode = mode
        self.apply()

    def _system_dark(self) -> bool:
        try:
            return QGuiApplication.styleHints().colorScheme() != Qt.ColorScheme.Light
        except AttributeError:
            return True

    def apply(self):
        dark = self._system_dark() if self.mode == "system" else self.mode == "dark"
        self.dark = dark
        self.tokens = DARK if dark else LIGHT
        if high_contrast():
            self.app.setStyleSheet("")
            self.app.setPalette(self.app.style().standardPalette())
        else:
            self.app.setPalette(_palette(self.tokens))
            self.app.setStyleSheet(_qss(self.tokens))
        log.debug("theme applied: %s", "dark" if dark else "light")
        self.changed.emit()

    def color(self, name: str) -> QColor:
        return QColor(self.tokens[name])


def _palette(t: dict) -> QPalette:
    p = QPalette()
    for role, key in ((QPalette.Window, "bg"), (QPalette.Base, "surface2"),
                      (QPalette.AlternateBase, "surface"), (QPalette.Button, "surface2"),
                      (QPalette.Text, "text"), (QPalette.WindowText, "text"),
                      (QPalette.ButtonText, "text"), (QPalette.ToolTipBase, "surface3"),
                      (QPalette.ToolTipText, "text"), (QPalette.PlaceholderText, "text3"),
                      (QPalette.Highlight, "accent"), (QPalette.Mid, "border")):
        p.setColor(role, QColor(t[key]))
    p.setColor(QPalette.HighlightedText, QColor("#FFFFFF"))
    p.setColor(QPalette.Disabled, QPalette.Text, QColor(t["text3"]))
    p.setColor(QPalette.Disabled, QPalette.ButtonText, QColor(t["text3"]))
    return p


def polish(widget, **props):
    """Set dynamic style properties and refresh the widget's style."""
    for k, v in props.items():
        widget.setProperty(k, v)
    widget.style().unpolish(widget)
    widget.style().polish(widget)
    return widget
