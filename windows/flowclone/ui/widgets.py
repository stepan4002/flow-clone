"""Small reusable widgets for the FlowClone window (styled through ui/theme.py)."""
from PySide6.QtCore import Property, QEasingCurve, QPropertyAnimation, QRectF, QSize, Qt, Signal
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPainterPath
from PySide6.QtWidgets import (QAbstractButton, QButtonGroup, QFrame, QHBoxLayout, QLabel,
                               QPushButton, QScrollArea, QSizePolicy, QVBoxLayout, QWidget)

from . import icons
from .theme import polish


def label(text: str = "", role: str | None = None, wrap: bool = False) -> QLabel:
    lb = QLabel(text)
    if role:
        lb.setProperty("role", role)
    lb.setWordWrap(wrap)
    lb.setTextInteractionFlags(Qt.TextSelectableByMouse if wrap else Qt.NoTextInteraction)
    return lb


def button(text: str, kind: str | None = None, icon_name: str | None = None, theme=None,
           tooltip: str | None = None) -> QPushButton:
    b = QPushButton(text)
    if kind:
        b.setProperty("kind", kind)
    if icon_name and theme is not None:
        b.setProperty("icon_name", icon_name)
        color = "#FFFFFF" if kind == "primary" else theme.tokens["text"]
        b.setIcon(icons.icon(icon_name, color))
        b.setIconSize(QSize(16, 16))
    if tooltip:
        b.setToolTip(tooltip)
        b.setAccessibleName(tooltip)
    b.setCursor(Qt.PointingHandCursor)
    return b


def chip(text: str, tooltip: str = "") -> QPushButton:
    b = QPushButton(f"{text}   ×")
    b.setProperty("chip", "true")
    b.setCursor(Qt.PointingHandCursor)
    if tooltip:
        b.setToolTip(tooltip)
        b.setAccessibleName(tooltip)
    return b


def clear_layout(layout):
    """Remove and delete every item (hidden at once, so nothing stale is painted)."""
    while layout.count():
        item = layout.takeAt(0)
        w = item.widget()
        if w is not None:
            w.hide()
            w.deleteLater()
        elif item.layout() is not None:
            clear_layout(item.layout())


def separator() -> QFrame:
    f = QFrame()
    f.setProperty("sep", "true")
    return f


class Card(QFrame):
    def __init__(self, title: str | None = None, subtitle: str | None = None, accent=False):
        super().__init__()
        self.setProperty("card", "accent" if accent else "true")
        self.body = QVBoxLayout(self)
        self.body.setContentsMargins(18, 16, 18, 16)
        self.body.setSpacing(10)
        self.body.setAlignment(Qt.AlignTop)
        if title:
            self.body.addWidget(label(title, "h3"))
        if subtitle:
            self.body.addWidget(label(subtitle, "muted", wrap=True))


class StatCard(QFrame):
    def __init__(self, icon_name: str, title: str, theme):
        super().__init__()
        self.setProperty("card", "true")
        self.theme = theme
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 14, 16, 14)
        lay.setSpacing(4)
        top = QHBoxLayout()
        self._icon = QLabel()
        self._icon_name = icon_name
        top.addWidget(self._icon)
        top.addWidget(label(title, "muted"))
        top.addStretch()
        lay.addLayout(top)
        self.value = label("—", "stat")
        lay.addWidget(self.value)
        self.hint = label("", "faint")
        lay.addWidget(self.hint)
        self.restyle()

    def restyle(self):
        self._icon.setPixmap(icons.pixmap(self._icon_name, self.theme.tokens["accent"], 16))

    def set(self, value: str, hint: str = ""):
        self.value.setText(value)
        self.hint.setText(hint)
        self.setAccessibleName(f"{value} {hint}")


class Toggle(QAbstractButton):
    """An on/off switch (keyboard: Space toggles)."""

    def __init__(self, theme, checked=False, accessible_name=""):
        super().__init__()
        self.theme = theme
        self.setCheckable(True)
        self.setChecked(checked)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.StrongFocus)
        self.setFixedSize(42, 24)
        self.setAccessibleName(accessible_name)
        self._pos = 1.0 if checked else 0.0
        self._anim = QPropertyAnimation(self, b"pos_", self)
        self._anim.setDuration(140)
        self._anim.setEasingCurve(QEasingCurve.OutCubic)
        self.toggled.connect(self._animate)

    def _animate(self, on):
        self._anim.stop()
        self._anim.setStartValue(self._pos)
        self._anim.setEndValue(1.0 if on else 0.0)
        self._anim.start()

    def _get_pos(self):
        return self._pos

    def _set_pos(self, v):
        self._pos = v
        self.update()

    pos_ = Property(float, _get_pos, _set_pos)

    def setChecked(self, on):  # noqa: N802 (Qt name)
        super().setChecked(on)
        self._pos = 1.0 if on else 0.0
        self.update()

    def paintEvent(self, _e):
        t = self.theme.tokens
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(1, 1, self.width() - 2, self.height() - 2)
        off, on = QColor(t["surface3"]), QColor(t["accent"])
        track = QColor(
            round(off.red() + (on.red() - off.red()) * self._pos),
            round(off.green() + (on.green() - off.green()) * self._pos),
            round(off.blue() + (on.blue() - off.blue()) * self._pos))
        if not self.isEnabled():
            track.setAlphaF(0.4)
        p.setPen(Qt.NoPen)
        p.setBrush(track)
        p.drawRoundedRect(r, r.height() / 2, r.height() / 2)
        d = r.height() - 6
        x = r.left() + 3 + (r.width() - d - 6) * self._pos
        p.setBrush(QColor("#FFFFFF"))
        p.drawEllipse(QRectF(x, r.top() + 3, d, d))
        if self.hasFocus():
            p.setBrush(Qt.NoBrush)
            p.setPen(QColor(t["accent_hover"]))
            p.drawRoundedRect(r.adjusted(-1, -1, 1, 1), r.height() / 2 + 1, r.height() / 2 + 1)
        p.end()

    def sizeHint(self):
        return QSize(42, 24)


class Segmented(QFrame):
    """A row of mutually exclusive buttons (e.g. Verbatim / Clean / Casual / Formal)."""
    changed = Signal(str)

    def __init__(self, options: list[tuple[str, str]], value: str, accessible_name=""):
        super().__init__()
        self.setProperty("segbar", "true")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(3, 3, 3, 3)
        lay.setSpacing(2)
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        self.buttons = {}
        for key, text in options:
            b = QPushButton(text)
            b.setProperty("seg", "true")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            b.setAccessibleName(f"{accessible_name}: {text}")
            bold = QFont(b.font())
            bold.setBold(True)  # the checked segment is bold: reserve room so it isn't clipped
            b.setMinimumWidth(QFontMetrics(bold).horizontalAdvance(text) + 30)
            self.group.addButton(b)
            self.buttons[key] = b
            lay.addWidget(b)
            b.clicked.connect(lambda _=False, k=key: self.changed.emit(k))
        self.set_value(value)

    def set_value(self, value: str):
        if value in self.buttons:
            self.buttons[value].setChecked(True)

    def value(self) -> str:
        for k, b in self.buttons.items():
            if b.isChecked():
                return k
        return ""


class BarChart(QWidget):
    """Words per day, last N days."""

    def __init__(self, theme):
        super().__init__()
        self.theme = theme
        self.series: list[tuple[str, int]] = []
        self.setMinimumHeight(120)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setAccessibleName("Words per day, last 30 days")

    def set_series(self, series):
        self.series = series
        total = sum(v for _, v in series)
        self.setToolTip(f"{total} words in the last {len(series)} days")
        self.update()

    def paintEvent(self, _e):
        t = self.theme.tokens
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        n = len(self.series)
        if not n:
            return
        top = max(v for _, v in self.series) or 1
        w, h = self.width(), self.height() - 18
        gap = 4
        bw = max(2.0, (w - gap * (n - 1)) / n)
        for i, (day, v) in enumerate(self.series):
            bh = max(3.0, (v / top) * (h - 4)) if v else 3.0
            x = i * (bw + gap)
            c = QColor(t["accent"] if v else t["surface3"])
            if i == n - 1 and v:
                c = QColor(t["accent_hover"])
            p.setPen(Qt.NoPen)
            p.setBrush(c)
            path = QPainterPath()
            path.addRoundedRect(QRectF(x, h - bh, bw, bh), min(3, bw / 2), min(3, bw / 2))
            p.drawPath(path)
        p.setPen(QColor(t["text3"]))
        f = QFont(self.font())
        f.setPointSizeF(8)
        p.setFont(f)
        p.drawText(QRectF(0, h + 2, 120, 16), Qt.AlignLeft, self.series[0][0][5:])
        p.drawText(QRectF(w - 120, h + 2, 120, 16), Qt.AlignRight, "today")
        p.end()


class EmptyState(QWidget):
    def __init__(self, icon_name: str, title: str, text: str, theme):
        super().__init__()
        self.theme = theme
        self._icon_name = icon_name
        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 30, 20, 30)
        lay.setAlignment(Qt.AlignCenter)
        self._icon = QLabel()
        self._icon.setAlignment(Qt.AlignCenter)
        lay.addWidget(self._icon)
        self.title = label(title, "h3")
        self.title.setAlignment(Qt.AlignCenter)
        lay.addWidget(self.title)
        self.text = label(text, "muted", wrap=True)
        self.text.setAlignment(Qt.AlignCenter)
        lay.addWidget(self.text)
        self.restyle()

    def set_text(self, title: str, text: str | None = None):
        self.title.setText(title)
        if text is not None:
            self.text.setText(text)

    def restyle(self):
        self._icon.setPixmap(icons.pixmap(self._icon_name, self.theme.tokens["text3"], 36))


def keycaps(keys: list[str]) -> QWidget:
    w = QWidget()
    lay = QHBoxLayout(w)
    lay.setContentsMargins(0, 0, 0, 0)
    lay.setSpacing(6)
    names = {"ctrl": "Ctrl", "windows": "Win", "win": "Win", "shift": "Shift", "alt": "Alt",
             "space": "Space", "esc": "Esc"}
    for i, k in enumerate(keys):
        if i:
            lay.addWidget(label("+", "muted"))
        lay.addWidget(label(names.get(k.lower(), k.capitalize()), "kbd"))
    lay.addStretch()
    return w


def page(title: str, subtitle: str | None = None) -> tuple[QScrollArea, QVBoxLayout]:
    """A scrollable page with a centred column; returns (widget, column layout)."""
    area = QScrollArea()
    area.setWidgetResizable(True)
    area.setFrameShape(QFrame.NoFrame)
    inner = QWidget()
    inner.setObjectName("Page")
    outer = QHBoxLayout(inner)
    outer.setContentsMargins(32, 28, 32, 28)
    col_w = QWidget()
    col_w.setMaximumWidth(920)
    col = QVBoxLayout(col_w)
    col.setContentsMargins(0, 0, 0, 0)
    col.setSpacing(14)
    outer.addWidget(col_w)
    head = QVBoxLayout()
    head.setSpacing(2)
    head.addWidget(label(title, "h1"))
    if subtitle:
        head.addWidget(label(subtitle, "muted", wrap=True))
    col.addLayout(head)
    col.addSpacing(6)
    area.setWidget(inner)
    return area, col


def row(*widgets, stretch_after=True, spacing=10) -> QHBoxLayout:
    lay = QHBoxLayout()
    lay.setSpacing(spacing)
    for w in widgets:
        if isinstance(w, int):
            lay.addSpacing(w)
        elif w is None:
            lay.addStretch()
        else:
            lay.addWidget(w)
    if stretch_after:
        lay.addStretch()
    return lay


def setting_row(title: str, description: str, control: QWidget) -> QWidget:
    """Title + description on the left, a control on the right."""
    w = QWidget()
    lay = QHBoxLayout(w)
    lay.setContentsMargins(0, 4, 0, 4)
    text = QVBoxLayout()
    text.setSpacing(1)
    text.addWidget(label(title))
    if description:
        text.addWidget(label(description, "faint", wrap=True))
    lay.addLayout(text, 1)
    lay.addSpacing(16)
    lay.addWidget(control, 0, Qt.AlignVCenter)
    control.setAccessibleName(control.accessibleName() or title)
    return w


def restyle_tree(widget, theme=None):
    """Re-polish a widget tree after a theme change (and re-colour button icons)."""
    for child in widget.findChildren(QWidget):
        if hasattr(child, "restyle"):
            child.restyle()
        name = child.property("icon_name") if isinstance(child, QPushButton) else None
        if name and theme is not None:
            color = "#FFFFFF" if child.property("kind") == "primary" else theme.tokens["text"]
            child.setIcon(icons.icon(name, color))
        polish(child)
