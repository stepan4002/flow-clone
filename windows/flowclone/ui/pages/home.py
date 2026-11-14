"""Home: how to dictate, insights (words, streak, speed, time saved), activity, recent dictations."""
from datetime import datetime

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QGridLayout, QHBoxLayout, QVBoxLayout, QWidget

__all__ = ["HomePage", "pretty_app"]

from ..widgets import (BarChart, Card, EmptyState, StatCard, button, keycaps, label, page,
                       separator)


def _greeting() -> str:
    h = datetime.now().hour
    return "Good morning" if 5 <= h < 12 else "Good afternoon" if h < 18 else "Good evening"


class HomePage:
    name = "home"

    def __init__(self, app):
        self.app = app
        theme = app.theme
        self.widget, col = page(_greeting(), "Hold the hotkey anywhere, talk, let go — "
                                             "your words appear where you're typing.")

        # how-to card
        how = Card(accent=True)
        r = QHBoxLayout()
        r.addWidget(label("Hold", "h3"))
        self._keys_holder = QHBoxLayout()
        r.addLayout(self._keys_holder)
        r.addWidget(label("to dictate  ·  double-tap for hands-free  ·  add Shift for Command Mode",
                          "muted"))
        r.addStretch()
        how.body.addLayout(r)
        col.addWidget(how)

        # stat cards
        grid = QGridLayout()
        grid.setSpacing(12)
        self.c_week = StatCard("type", "Words this week", theme)
        self.c_streak = StatCard("flame", "Streak", theme)
        self.c_wpm = StatCard("gauge", "Speaking speed", theme)
        self.c_saved = StatCard("hourglass", "Time saved", theme)
        for i, c in enumerate((self.c_week, self.c_streak, self.c_wpm, self.c_saved)):
            grid.addWidget(c, 0, i)
        col.addLayout(grid)

        # activity + top apps
        mid = QHBoxLayout()
        mid.setSpacing(12)
        act = Card("Activity", "Words per day, last 30 days")
        self.chart = BarChart(theme)
        act.body.addWidget(self.chart)
        mid.addWidget(act, 3)
        self.apps_card = Card("Where you dictate")
        self.apps_box = QVBoxLayout()
        self.apps_box.setSpacing(6)
        self.apps_card.body.addLayout(self.apps_box)
        self.apps_card.body.addStretch()
        mid.addWidget(self.apps_card, 2)
        col.addLayout(mid)

        # recent
        self.recent = Card("Recent dictations")
        self.recent_box = QVBoxLayout()
        self.recent_box.setSpacing(2)
        self.recent.body.addLayout(self.recent_box)
        more = button("Open history", "ghost", "history", theme)
        more.clicked.connect(lambda: app.window.show_page("history"))
        self.recent.body.addWidget(more, 0, Qt.AlignLeft)
        col.addWidget(self.recent)
        col.addStretch()

    def refresh(self):
        s = self.app.stats
        week = s.this_week()
        cur, best = s.streak()
        wpm = s.wpm()
        saved = s.time_saved_minutes()
        today = s.today()
        self.c_week.set(f"{week:,}", f"{today:,} today")
        self.c_streak.set(f"{cur} day{'s' if cur != 1 else ''}", f"best {best}")
        self.c_wpm.set(f"{wpm} wpm" if wpm else "—",
                       "vs ~40 typing" if wpm else "after a minute of dictation")
        self.c_saved.set(_minutes(saved), "compared with typing at 40 wpm")
        self.chart.set_series(s.days_series(30))

        _clear(self._keys_holder)
        self._keys_holder.addWidget(keycaps(self.app.config["hotkeys"]["push_to_talk"]))

        _clear(self.apps_box)
        top = s.top_apps(5)
        if not top:
            self.apps_box.addWidget(label("Your most-used apps will show up here.", "muted", wrap=True))
        total = sum(v for _, v in top) or 1
        for exe, words in top:
            line = QHBoxLayout()
            line.addWidget(label(pretty_app(exe)))
            line.addStretch()
            line.addWidget(label(f"{round(100 * words / total)}%", "muted"))
            self.apps_box.addLayout(line)

        _clear(self.recent_box)
        entries = self.app.history.entries(limit=5)
        if not entries:
            self.recent_box.addWidget(EmptyState(
                "mic", "No dictations yet",
                "Hold the hotkey in any text field, say something, and let go.", self.app.theme))
        for i, e in enumerate(entries):
            if i:
                self.recent_box.addWidget(separator())
            self.recent_box.addWidget(_RecentRow(e, self.app))

    def restyle(self):
        for c in (self.c_week, self.c_streak, self.c_wpm, self.c_saved):
            c.restyle()
        self.chart.update()


class _RecentRow(QWidget):
    def __init__(self, entry, app):
        super().__init__()
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 4, 0, 4)
        text = QVBoxLayout()
        text.setSpacing(1)
        t = label(entry["text"].replace("\n", " ⏎ "), wrap=False)
        t.setMaximumWidth(700)
        t.setToolTip(entry["text"][:2000])
        fm = t.fontMetrics()
        t.setText(fm.elidedText(t.text(), Qt.ElideRight, 640))
        text.addWidget(t)
        text.addWidget(label(f"{entry['ts'][11:16]} · {pretty_app(entry['app'])} · "
                             f"{entry['words']} words", "faint"))
        lay.addLayout(text, 1)
        b = button("", "ghost", "copy", app.theme, tooltip="Copy")
        b.clicked.connect(lambda: QApplication.clipboard().setText(entry["text"]))
        lay.addWidget(b)


def _clear(layout):
    while layout.count():
        item = layout.takeAt(0)
        if item.widget():
            item.widget().deleteLater()
        elif item.layout():
            _clear(item.layout())


def _minutes(m: int) -> str:
    if m < 60:
        return f"{m} min"
    return f"{m // 60} h {m % 60} min"


def pretty_app(exe: str) -> str:
    if not exe:
        return "Unknown app"
    names = {"code.exe": "VS Code", "chrome.exe": "Chrome", "msedge.exe": "Edge",
             "firefox.exe": "Firefox", "winword.exe": "Word", "outlook.exe": "Outlook",
             "olk.exe": "Outlook (new)", "slack.exe": "Slack", "discord.exe": "Discord",
             "notepad.exe": "Notepad", "ms-teams.exe": "Teams", "teams.exe": "Teams (classic)",
             "whatsapp.exe": "WhatsApp", "telegram.exe": "Telegram", "notion.exe": "Notion",
             "obsidian.exe": "Obsidian", "cursor.exe": "Cursor", "claude.exe": "Claude",
             "windowsterminal.exe": "Terminal", "explorer.exe": "Explorer"}
    return names.get(exe.lower(), exe.rsplit(".", 1)[0].capitalize())
