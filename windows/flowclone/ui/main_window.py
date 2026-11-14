"""The FlowClone window: a sidebar and one page per section. Closing it only hides it."""
from PySide6.QtCore import QRect, QSize, Qt
from PySide6.QtGui import QGuiApplication, QIcon, QPixmap
from PySide6.QtWidgets import (QButtonGroup, QHBoxLayout, QLabel, QMainWindow, QPushButton,
                               QStackedWidget, QVBoxLayout, QWidget)

from .. import __version__, paths
from . import icons
from .pages.about import AboutPage
from .pages.dictionary import DictionaryPage, SnippetsPage
from .pages.history import HistoryPage
from .pages.home import HomePage
from .pages.settings import SettingsPage
from .pages.styles import StylesPage
from .theme import polish
from .widgets import label, restyle_tree
from .winchrome import apply_titlebar, force_foreground

NAV = [("home", "Home", "home"), ("history", "History", "history"),
       ("dictionary", "Dictionary", "book"), ("snippets", "Snippets", "zap"),
       ("styles", "Styles", "palette"), ("settings", "Settings", "sliders"),
       ("about", "About", "info")]
PAGES = {"home": HomePage, "history": HistoryPage, "dictionary": DictionaryPage,
         "snippets": SnippetsPage, "styles": StylesPage, "settings": SettingsPage,
         "about": AboutPage}


class MainWindow(QMainWindow):
    def __init__(self, app):
        super().__init__()
        self.app = app
        self.theme = app.theme
        self.setWindowTitle("FlowClone")
        self.setWindowIcon(QIcon(str(paths.resource("assets/flowclone.ico"))))
        self.setMinimumSize(880, 580)
        self.resize(1080, 720)

        root = QWidget()
        root.setObjectName("Root")
        lay = QHBoxLayout(root)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)

        side = QWidget()
        side.setObjectName("Sidebar")
        side.setFixedWidth(224)
        sl = QVBoxLayout(side)
        sl.setContentsMargins(14, 18, 14, 14)
        sl.setSpacing(4)
        brand = QHBoxLayout()
        logo = QLabel()
        png = paths.resource("assets/flowclone.png")
        if png.exists():
            px = QPixmap(str(png))
            px.setDevicePixelRatio(2)
            logo.setPixmap(px.scaled(56, 56, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        brand.addWidget(logo)
        brand.addWidget(label("FlowClone", "brand"))
        brand.addStretch()
        sl.addLayout(brand)
        sl.addSpacing(14)

        self.group = QButtonGroup(self)
        self.nav: dict[str, QPushButton] = {}
        for key, text, icon_name in NAV:
            b = QPushButton(f"  {text}")
            b.setProperty("nav", "true")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            b.setIconSize(QSize(18, 18))
            b.setAccessibleName(text)
            b.clicked.connect(lambda _=False, k=key: self.show_page(k))
            self.group.addButton(b)
            self.nav[key] = (b, icon_name)
            sl.addWidget(b)
            if key == "styles":
                sl.addSpacing(10)
        sl.addStretch()
        self.status = label("Starting…", "chip-warn")
        self.status.setAccessibleName("Speech model status")
        sl.addWidget(self.status, 0, Qt.AlignLeft)
        sl.addWidget(label(f"v{__version__}", "faint"))
        lay.addWidget(side)

        self.stack = QStackedWidget()
        lay.addWidget(self.stack, 1)
        self.setCentralWidget(root)

        self.pages = {}
        self.current = None
        self._restyle_nav()
        self.theme.changed.connect(self._theme_changed)
        app.bridge.model_status.connect(self._model_status)
        app.bridge.dictation_done.connect(self._dictation_done)
        self._model_status(*app.model_status)
        self._restore_geometry()

    # ---------- pages ----------

    def page(self, key):
        if key not in self.pages:
            p = PAGES[key](self.app)
            self.pages[key] = p
            self.stack.addWidget(p.widget)
        return self.pages[key]

    def show_page(self, key: str):
        key = key if key in PAGES else "home"
        p = self.page(key)
        try:
            p.refresh()
        except Exception:
            import logging
            logging.getLogger("flowclone").exception("page refresh failed: %s", key)
        self.stack.setCurrentWidget(p.widget)
        self.nav[key][0].setChecked(True)
        self.current = key

    def open(self, key: str = "home"):
        if self.current is None or key != "home" or not self.isVisible():
            self.show_page(key if key else "home")
        force_foreground(self)
        apply_titlebar(self, self.theme.dark, self.theme.tokens["bg"])

    def _dictation_done(self):
        if self.isVisible() and self.current in ("home", "history"):
            self.pages[self.current].refresh()

    # ---------- look ----------

    def _restyle_nav(self):
        t = self.theme.tokens
        for key, (b, icon_name) in self.nav.items():
            b.setIcon(icons.icon(icon_name, t["text"]))
            polish(b)

    def _theme_changed(self):
        icons.clear_cache()
        self._restyle_nav()
        for p in self.pages.values():
            if hasattr(p, "restyle"):
                p.restyle()
        restyle_tree(self)
        apply_titlebar(self, self.theme.dark, self.theme.tokens["bg"])

    def _model_status(self, state, pct):
        text, role = {"ready": ("● Ready", "chip-ok"), "error": ("● Speech model error", "chip-err"),
                      "downloading": (f"● Downloading {pct}%", "chip-warn"),
                      "loading": ("● Loading model…", "chip-warn")}.get(state, (state, "chip"))
        self.status.setText(text)
        self.status.setProperty("role", role)
        polish(self.status)
        self.status.setToolTip("Speech model ready" if state == "ready" else
                               "Settings → Speech model to retry" if state == "error" else "")

    def showEvent(self, e):  # noqa: N802
        super().showEvent(e)
        apply_titlebar(self, self.theme.dark, self.theme.tokens["bg"])

    # ---------- geometry ----------

    def _restore_geometry(self):
        g = (self.app.config.get("ui") or {}).get("window")
        if isinstance(g, list) and len(g) == 4:
            rect = QRect(*[int(v) for v in g])
            if any(s.availableGeometry().intersects(rect) for s in QGuiApplication.screens()):
                self.setGeometry(rect)
                return
        screen = QGuiApplication.primaryScreen()
        if screen:
            c = screen.availableGeometry().center()
            self.move(c.x() - self.width() // 2, c.y() - self.height() // 2)

    def closeEvent(self, e):  # noqa: N802
        g = self.geometry()
        self.app.save_settings(lambda c: c["ui"].__setitem__(
            "window", [g.x(), g.y(), g.width(), g.height()]), live=False)
        e.ignore()
        self.hide()
