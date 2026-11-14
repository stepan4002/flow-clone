"""Styles: how your words are written in each kind of app (chat, email, code, documents)."""
import threading

from PySide6.QtCore import QObject, Qt, Signal
from PySide6.QtWidgets import QComboBox, QGridLayout, QHBoxLayout, QLineEdit, QPushButton, QWidget

from ... import styles as style_rules
from ..widgets import Card, Segmented, Toggle, button, chip, clear_layout, label, page, setting_row
from .dictionary import FlowLayout
from .home import pretty_app

_STYLE_OPTIONS = [("verbatim", "Verbatim"), ("clean", "Clean"), ("casual", "Casual"),
                  ("formal", "Formal")]
_CATS = {
    "messaging": ("Messaging", "Slack, Teams, WhatsApp, Discord…"),
    "email": ("Email", "Outlook, Thunderbird…"),
    "code": ("Code & terminals", "VS Code, Cursor, terminals — keep your words exact"),
    "documents": ("Documents & notes", "Word, Notion, Obsidian, OneNote…"),
}
_SAMPLE = "um so i think we should uh move the meeting to thursday period can you let the team know"


def running_apps() -> list[str]:
    """Exe names of visible top-level windows (for 'add from open apps')."""
    names = set()
    try:
        import psutil
        import win32gui
        import win32process

        def cb(hwnd, _):
            if win32gui.IsWindowVisible(hwnd) and win32gui.GetWindowText(hwnd):
                try:
                    _, pid = win32process.GetWindowThreadProcessId(hwnd)
                    names.add(psutil.Process(pid).name().lower())
                except Exception:
                    pass
            return True
        win32gui.EnumWindows(cb, None)
    except Exception:
        pass
    names.discard("flowclone.exe")
    names.discard("python.exe")
    names.discard("pythonw.exe")
    return sorted(names)


class _Preview(QObject):
    done = Signal(str, str)


class _CategoryCard(Card):
    def __init__(self, page_, key):
        title, sub = _CATS[key]
        super().__init__(title, sub)
        self.page, self.key = page_, key
        cat = page_.app.config["styles"]["categories"][key]
        self.seg = Segmented(_STYLE_OPTIONS, cat.get("style", "clean"), f"{title} style")
        self.seg.changed.connect(self._style_changed)
        self.body.addWidget(self.seg, 0, Qt.AlignLeft)
        self.apps_host = QWidget()
        self.flow = FlowLayout(self.apps_host)
        self.body.addWidget(self.apps_host)
        add = QHBoxLayout()
        self.picker = QComboBox()
        self.picker.setEditable(True)
        self.picker.setInsertPolicy(QComboBox.NoInsert)
        self.picker.lineEdit().setPlaceholderText("Add an app (e.g. slack.exe)…")
        self.picker.setAccessibleName(f"Add app to {title}")
        b = button("Add", None, "plus", page_.app.theme)
        b.clicked.connect(self._add)
        self.picker.lineEdit().returnPressed.connect(self._add)
        add.addWidget(self.picker, 1)
        add.addWidget(b)
        self.body.addLayout(add)
        self.render()

    def apps(self) -> list[str]:
        return list(self.page.app.config["styles"]["categories"][self.key].get("apps", []))

    def render(self):
        clear_layout(self.flow)
        for exe in self.apps():
            c = chip(pretty_app(exe), f"Remove {exe}")
            c.clicked.connect(lambda _=False, e=exe: self._remove(e))
            self.flow.addWidget(c)
        self.apps_host.updateGeometry()

    def fill_picker(self, names):
        self.picker.clear()
        self.picker.addItems([n for n in names if n not in self.apps()])
        self.picker.setCurrentText("")

    def _style_changed(self, style):
        self.page.app.save_settings(
            lambda c: c["styles"]["categories"][self.key].__setitem__("style", style))

    def _add(self):
        exe = self.picker.currentText().strip().lower()
        if not exe:
            return
        if not exe.endswith(".exe"):
            exe += ".exe"

        def mut(c):
            for k, cat in c["styles"]["categories"].items():  # an app is in one category only
                cat["apps"] = [a for a in cat.get("apps", []) if a.lower() != exe]
            c["styles"]["categories"][self.key]["apps"].append(exe)
            if self.key == "messaging" and exe not in c["casual_apps"]:
                c["casual_apps"].append(exe)       # the script install reads this list
        self.page.app.save_settings(mut)
        self.page.render_all()

    def _remove(self, exe):
        def mut(c):
            cat = c["styles"]["categories"][self.key]
            cat["apps"] = [a for a in cat.get("apps", []) if a != exe]
            if self.key == "messaging":
                c["casual_apps"] = [a for a in c["casual_apps"] if a != exe]
        self.page.app.save_settings(mut)
        self.render()


class StylesPage:
    name = "styles"

    def __init__(self, app):
        self.app = app
        theme = app.theme
        self.widget, col = page("Styles", "FlowClone writes differently depending on where you "
                                          "are typing. Rules work offline; with AI turned on "
                                          "(Settings → AI) the text is rewritten in the style.")
        top = Card()
        self.enabled = Toggle(theme, app.config["styles"].get("enabled", True), "Per-app styles")
        self.enabled.toggled.connect(lambda on: app.save_settings(
            lambda c: c["styles"].__setitem__("enabled", on)))
        top.body.addWidget(setting_row("Per-app styles", "Off: one clean style everywhere "
                                       "(casual apps still skip the final full stop).", self.enabled))
        self.default = Segmented(_STYLE_OPTIONS, app.config["styles"].get("default", "clean"),
                                 "Style for other apps")
        self.default.changed.connect(lambda s: app.save_settings(
            lambda c: c["styles"].__setitem__("default", s)))
        top.body.addWidget(setting_row("All other apps", "", self.default))
        col.addWidget(top)

        grid = QGridLayout()
        grid.setSpacing(12)
        self.cards = [_CategoryCard(self, k) for k in _CATS]
        for i, c in enumerate(self.cards):
            grid.addWidget(c, i // 2, i % 2)
        col.addLayout(grid)

        prev = Card("Try it", "See what a sentence becomes in each style.")
        r = QHBoxLayout()
        self.sample = QLineEdit(_SAMPLE)
        self.sample.setAccessibleName("Sample sentence")
        self.pv_style = Segmented(_STYLE_OPTIONS, "clean", "Preview style")
        r.addWidget(self.sample, 1)
        prev.body.addLayout(r)
        prev.body.addWidget(self.pv_style, 0, Qt.AlignLeft)
        self.rule_out = label("", wrap=True)
        self.ai_out = label("", "muted", wrap=True)
        prev.body.addWidget(label("Rules (always on)", "faint"))
        prev.body.addWidget(self.rule_out)
        prev.body.addWidget(label("With AI", "faint"))
        prev.body.addWidget(self.ai_out)
        self.ai_btn = button("Preview with AI", None, "sparkles", theme)
        self.ai_btn.clicked.connect(self._ai_preview)
        prev.body.addWidget(self.ai_btn, 0, Qt.AlignLeft)
        col.addWidget(prev)
        col.addStretch()
        self._sig = _Preview()
        self._sig.done.connect(self._ai_done)
        self.sample.textChanged.connect(lambda _: self._preview())
        self.pv_style.changed.connect(lambda _: self._preview())
        self._preview()

    def render_all(self):
        for c in self.cards:
            c.render()

    def refresh(self):
        names = running_apps()
        for c in self.cards:
            c.fill_picker(names)
        self.render_all()
        ai_on = self.app.config["llm"].get("enabled", False)
        self.ai_btn.setEnabled(ai_on)
        self.ai_out.setText("" if ai_on else "Turn on AI in Settings to preview AI rewriting.")

    def _preview(self):
        text, _ = style_rules.apply_rules(self.sample.text(), self.pv_style.value(),
                                          self.app.config["cleanup"])
        self.rule_out.setText(text or "—")

    def _ai_preview(self):
        style = self.pv_style.value()
        text = self.rule_out.text()
        self.ai_out.setText("Asking the AI…")
        self.ai_btn.setEnabled(False)

        def work():
            out = self.app.polisher.rewrite(text, style_rules.llm_instruction(style))
            self._sig.done.emit(style, out or "The AI didn't answer — check Settings → AI.")
        threading.Thread(target=work, daemon=True).start()

    def _ai_done(self, _style, text):
        self.ai_out.setText(text)
        self.ai_btn.setEnabled(True)
