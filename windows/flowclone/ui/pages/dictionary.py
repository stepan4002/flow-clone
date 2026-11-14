"""Dictionary: your words (correct spelling and casing), corrections for misheard words, and
replacements (a short thing you say -> the text that is inserted)."""
from PySide6.QtCore import QPoint, QRect, QSize, Qt
from PySide6.QtWidgets import (QAbstractItemView, QHBoxLayout, QHeaderView, QLayout, QLineEdit,
                               QPushButton, QStackedWidget, QTableWidget, QTableWidgetItem,
                               QVBoxLayout, QWidget)

from ..widgets import Card, EmptyState, Segmented, button, chip, clear_layout, label, page


class FlowLayout(QLayout):
    """Wraps child widgets onto new lines (for word chips)."""

    def __init__(self, parent=None, spacing=6):
        super().__init__(parent)
        self._items = []
        self._spacing = spacing
        self.setContentsMargins(0, 0, 0, 0)

    def addItem(self, item):  # noqa: N802
        self._items.append(item)

    def count(self):
        return len(self._items)

    def itemAt(self, i):  # noqa: N802
        return self._items[i] if 0 <= i < len(self._items) else None

    def takeAt(self, i):  # noqa: N802
        return self._items.pop(i) if 0 <= i < len(self._items) else None

    def expandingDirections(self):  # noqa: N802
        return Qt.Orientations(0)

    def hasHeightForWidth(self):  # noqa: N802
        return True

    def heightForWidth(self, w):  # noqa: N802
        return self._do(QRect(0, 0, w, 0), dry=True)

    def setGeometry(self, rect):  # noqa: N802
        super().setGeometry(rect)
        self._do(rect, dry=False)

    def sizeHint(self):  # noqa: N802
        return self.minimumSize()

    def minimumSize(self):  # noqa: N802
        s = QSize()
        for it in self._items:
            s = s.expandedTo(it.minimumSize())
        return s

    def _do(self, rect, dry):
        x, y, line = rect.x(), rect.y(), 0
        for it in self._items:
            hint = it.sizeHint()
            if x + hint.width() > rect.right() and line > 0:
                x, y, line = rect.x(), y + line + self._spacing, 0
            if not dry:
                it.setGeometry(QRect(QPoint(x, y), hint))
            x += hint.width() + self._spacing
            line = max(line, hint.height())
        return y + line - rect.y()


_NL = "\u23ce"  # ⏎ — how a line break is shown (and typed) in the one-line table cells


def _show(v: str) -> str:
    return v.replace("\r\n", "\n").replace("\n", _NL)


def _parse(v: str) -> str:
    return v.replace(_NL, "\n")


class PairTable(QWidget):
    """Two editable columns with add / remove; calls on_change(dict) after every edit."""

    def __init__(self, left: str, right: str, data: dict, on_change, theme, placeholder=("", "")):
        super().__init__()
        self.on_change = on_change
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        add = QHBoxLayout()
        self.a = QLineEdit()
        self.a.setPlaceholderText(placeholder[0])
        self.a.setAccessibleName(left)
        self.b = QLineEdit()
        self.b.setPlaceholderText(placeholder[1])
        self.b.setAccessibleName(right)
        plus = button("Add", "primary", "plus", theme)
        add.addWidget(self.a, 2)
        add.addWidget(label("→", "muted"))
        add.addWidget(self.b, 3)
        add.addWidget(plus)
        lay.addLayout(add)
        self.table = QTableWidget(0, 2)
        self.table.setHorizontalHeaderLabels([left, right])
        self.table.verticalHeader().hide()
        self.table.setShowGrid(False)
        self.table.verticalHeader().setDefaultSectionSize(34)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Interactive)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.table.setColumnWidth(0, 220)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setMinimumHeight(220)
        self.table.setAccessibleName(f"{left} and {right}")
        lay.addWidget(self.table)
        rm = button("Remove selected", "ghost", "trash", theme)
        lay.addWidget(rm, 0, Qt.AlignLeft)
        self.empty = label("Nothing yet — add one above.", "faint")
        lay.addWidget(self.empty)
        for k, v in data.items():
            self._append(k, v)
        self._update_empty()
        plus.clicked.connect(self._add)
        self.b.returnPressed.connect(self._add)
        rm.clicked.connect(self._remove)
        self.table.itemChanged.connect(lambda _: self._emit())

    def _append(self, k, v):
        r = self.table.rowCount()
        self.table.blockSignals(True)
        self.table.insertRow(r)
        self.table.setItem(r, 0, QTableWidgetItem(k))
        self.table.setItem(r, 1, QTableWidgetItem(_show(v)))
        self.table.blockSignals(False)

    def _add(self):
        k, v = self.a.text().strip(), self.b.text().strip()
        if k and v:
            self._append(k, v)
            self.a.clear()
            self.b.clear()
            self.a.setFocus()
            self._emit()

    def _remove(self):
        rows = sorted({i.row() for i in self.table.selectedIndexes()}, reverse=True)
        for r in rows:
            self.table.removeRow(r)
        self._emit()

    def data(self) -> dict:
        out = {}
        for r in range(self.table.rowCount()):
            a, b = self.table.item(r, 0), self.table.item(r, 1)
            if a and b and a.text().strip():
                out[a.text().strip()] = _parse(b.text())
        return out

    def set_data(self, data: dict):
        """Show `data` (e.g. after the file was changed outside this page)."""
        if data == self.data():
            return
        self.table.blockSignals(True)
        self.table.setRowCount(0)
        self.table.blockSignals(False)
        for k, v in data.items():
            self._append(k, v)
        self._update_empty()

    def _emit(self):
        self._update_empty()
        self.on_change(self.data())

    def _update_empty(self):
        self.empty.setVisible(self.table.rowCount() == 0)


class DictionaryPage:
    name = "dictionary"

    def __init__(self, app):
        self.app = app
        theme = app.theme
        self.widget, col = page("Dictionary", "Teach FlowClone your names, jargon and shortcuts. "
                                              "Changes apply to your next dictation.")
        self.tabs = Segmented([("words", "Words"), ("corrections", "Corrections"),
                               ("replacements", "Replacements")], "words", "Dictionary section")
        col.addWidget(self.tabs, 0, Qt.AlignLeft)
        self.stack = QStackedWidget()
        col.addWidget(self.stack)
        lex = app.lexicon

        # words
        words = Card("Your words", "Names and terms FlowClone should always spell your way, "
                                    "e.g. Kubernetes, GitHub, Siobhán.")
        add = QHBoxLayout()
        self.word_in = QLineEdit()
        self.word_in.setPlaceholderText("Add a word or name…")
        self.word_in.setAccessibleName("New word")
        add_b = button("Add", "primary", "plus", theme)
        add.addWidget(self.word_in, 1)
        add.addWidget(add_b)
        words.body.addLayout(add)
        self.chips_host = QWidget()
        self.chips = FlowLayout(self.chips_host)
        words.body.addWidget(self.chips_host)
        self.words_empty = EmptyState("book", "No words yet",
                                      "Add names and terms you use often.", theme)
        words.body.addWidget(self.words_empty)
        self.word_in.returnPressed.connect(self._add_word)
        add_b.clicked.connect(self._add_word)
        self.stack.addWidget(words)

        corr = Card("Corrections", "Always fix a word that is misheard — e.g. Eric → Erik.")
        self.corr = PairTable("Heard", "Should be", lex.corrections, lambda _: self._save(), theme,
                              ("Heard (e.g. Eric)", "Should be (e.g. Erik)"))
        corr.body.addWidget(self.corr)
        self.stack.addWidget(corr)

        repl = Card("Replacements", "Say something short, insert something long — "
                                    "e.g. \"my zoom link\" → https://…  Type ⏎ for a new line.")
        self.repl = PairTable("You say", "Inserted text", lex.replacements, lambda _: self._save(),
                              theme, ("You say", "Inserted text"))
        repl.body.addWidget(self.repl)
        self.stack.addWidget(repl)

        self.tabs.changed.connect(lambda k: self.stack.setCurrentIndex(
            ["words", "corrections", "replacements"].index(k)))
        col.addStretch()
        self._words = list(lex.words)
        self._render_chips()

    def _render_chips(self):
        clear_layout(self.chips)
        for w in self._words:
            c = chip(w, f"Remove {w}")
            c.clicked.connect(lambda _=False, word=w: self._remove_word(word))
            self.chips.addWidget(c)
        self.words_empty.setVisible(not self._words)
        self.chips_host.setVisible(bool(self._words))
        self.chips_host.updateGeometry()

    def _add_word(self):
        w = self.word_in.text().strip()
        if w and w not in self._words:
            self._words.append(w)
            self._render_chips()
            self._save()
        self.word_in.clear()

    def _remove_word(self, w):
        if w in self._words:
            self._words.remove(w)
            self._render_chips()
            self._save()

    def _save(self):
        self.app.lexicon.save_dictionary(self._words, self.corr.data(), self.repl.data())

    def refresh(self):
        # Always show what is on disk now (hand edits, or changes picked up by a dictation).
        lex = self.app.lexicon
        lex.reload_if_changed()
        if self._words != lex.words:
            self._words = list(lex.words)
            self._render_chips()
        self.corr.set_data(lex.corrections)
        self.repl.set_data(lex.replacements)


class SnippetsPage:
    name = "snippets"

    def __init__(self, app):
        self.app = app
        self.widget, col = page("Snippets", "Say a trigger phrase while dictating and FlowClone "
                                            "inserts the full text — your email, a sign-off, "
                                            "an address, a meeting link.")
        card = Card()
        self.table = PairTable("Trigger phrase", "Text inserted", app.lexicon.snippets,
                               lambda d: app.lexicon.save_snippets(d), app.theme,
                               ("Trigger, e.g. \"my address\"", "Text to insert"))
        card.body.addWidget(self.table)
        col.addWidget(card)
        tip = Card(accent=True)
        tip.body.addWidget(label("Tip: say the trigger anywhere in a sentence — "
                                 "\"send it to my address please\".", "muted", wrap=True))
        col.addWidget(tip)
        col.addStretch()

    def refresh(self):
        self.app.lexicon.reload_if_changed()
        self.table.set_data(self.app.lexicon.snippets)
