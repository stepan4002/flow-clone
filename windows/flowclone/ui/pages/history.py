"""History: search every dictation, see the raw transcript, copy, paste again, delete."""
from PySide6.QtCore import QAbstractTableModel, QModelIndex, Qt, QTimer
from PySide6.QtWidgets import (QAbstractItemView, QApplication, QComboBox, QHBoxLayout,
                               QHeaderView, QLineEdit, QMessageBox, QPlainTextEdit, QSplitter,
                               QStackedWidget, QTableView, QVBoxLayout, QWidget)

from .. import icons
from ..widgets import Card, EmptyState, button, label, page
from .home import pretty_app

_COLS = ("When", "App", "Text", "Words")


class _Model(QAbstractTableModel):
    def __init__(self):
        super().__init__()
        self.rows: list[dict] = []

    def set_rows(self, rows):
        self.beginResetModel()
        self.rows = rows
        self.endResetModel()

    def rowCount(self, parent=QModelIndex()):  # noqa: N802
        return 0 if parent.isValid() else len(self.rows)

    def columnCount(self, parent=QModelIndex()):  # noqa: N802
        return 0 if parent.isValid() else len(_COLS)

    def headerData(self, section, orientation, role=Qt.DisplayRole):  # noqa: N802
        if role == Qt.DisplayRole and orientation == Qt.Horizontal:
            return _COLS[section]
        return None

    def data(self, index, role=Qt.DisplayRole):
        e = self.rows[index.row()]
        c = index.column()
        if role == Qt.DisplayRole:
            if c == 0:
                return e["ts"][5:16].replace("-", "/", 1)
            if c == 1:
                return pretty_app(e["app"])
            if c == 2:
                return e["text"].replace("\n", " ⏎ ")[:300]
            return e["words"]
        if role == Qt.ToolTipRole and c == 2:
            return e["text"][:1500]
        if role == Qt.TextAlignmentRole and c == 3:
            return int(Qt.AlignRight | Qt.AlignVCenter)
        return None


class HistoryPage:
    name = "history"

    def __init__(self, app):
        self.app = app
        theme = app.theme
        self.widget, col = page("History", "Everything you dictated, on this computer only.")

        bar = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText("Search your dictations…")
        self.search.setClearButtonEnabled(True)
        self.search.setAccessibleName("Search history")
        self.search_action = self.search.addAction(icons.icon("search", theme.tokens["text3"]),
                                                   QLineEdit.LeadingPosition)
        self.app_filter = QComboBox()
        self.app_filter.setAccessibleName("Filter by app")
        self.app_filter.setMinimumWidth(160)
        bar.addWidget(self.search, 1)
        bar.addWidget(self.app_filter)
        col.addLayout(bar)

        self._debounce = QTimer(self.widget)
        self._debounce.setSingleShot(True)
        self._debounce.setInterval(200)
        self._debounce.timeout.connect(self._reload)
        self.search.textChanged.connect(lambda _: self._debounce.start())
        self.app_filter.currentIndexChanged.connect(lambda _: self._reload())

        self.model = _Model()
        self.table = QTableView()
        self.table.setModel(self.model)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.table.verticalHeader().hide()
        self.table.setShowGrid(False)
        self.table.setWordWrap(False)
        self.table.setTextElideMode(Qt.ElideRight)
        self.table.setAlternatingRowColors(False)
        self.table.verticalHeader().setDefaultSectionSize(36)
        h = self.table.horizontalHeader()
        h.setSectionResizeMode(0, QHeaderView.ResizeToContents)
        h.setSectionResizeMode(1, QHeaderView.ResizeToContents)
        h.setSectionResizeMode(2, QHeaderView.Stretch)
        h.setSectionResizeMode(3, QHeaderView.ResizeToContents)
        h.setHighlightSections(False)
        self.table.setAccessibleName("Dictations")
        self.table.selectionModel().currentRowChanged.connect(lambda *_: self._show_detail())
        self.table.doubleClicked.connect(lambda _: self._copy())

        self.empty = EmptyState("history", "Nothing here yet",
                                "Your dictations will appear here.", theme)
        self.stack = QStackedWidget()
        self.stack.addWidget(self.table)
        self.stack.addWidget(self.empty)

        detail = Card()
        self.detail_meta = label("", "faint")
        detail.body.addWidget(self.detail_meta)
        self.detail_text = QPlainTextEdit()
        self.detail_text.setReadOnly(True)
        self.detail_text.setAccessibleName("Selected dictation")
        detail.body.addWidget(self.detail_text, 1)
        self.raw_label = label("", "faint", wrap=True)
        detail.body.addWidget(self.raw_label)
        actions = QHBoxLayout()
        self.b_copy = button("Copy", None, "copy", theme)
        self.b_paste = button("Paste into app", None, "paste", theme)
        self.b_delete = button("Delete", "danger", "trash", theme)
        for b in (self.b_copy, self.b_paste):
            actions.addWidget(b)
        actions.addStretch()
        actions.addWidget(self.b_delete)
        detail.body.addLayout(actions)
        self.b_copy.clicked.connect(self._copy)
        self.b_paste.clicked.connect(self._paste)
        self.b_delete.clicked.connect(self._delete)

        split = QSplitter(Qt.Vertical)
        split.addWidget(self.stack)
        split.addWidget(detail)
        split.setStretchFactor(0, 3)
        split.setStretchFactor(1, 2)
        split.setMinimumHeight(520)
        col.addWidget(split, 1)

        foot = QHBoxLayout()
        self.count = label("", "faint")
        foot.addWidget(self.count)
        foot.addStretch()
        clear = button("Clear history…", "ghost", "trash", theme)
        clear.clicked.connect(self._clear)
        foot.addWidget(clear)
        col.addLayout(foot)

    # ---------- data ----------

    def refresh(self):
        current = self.app_filter.currentData()
        self.app_filter.blockSignals(True)
        self.app_filter.clear()
        self.app_filter.addItem("All apps", None)
        for exe in self.app.history.apps():
            self.app_filter.addItem(pretty_app(exe), exe)
        i = self.app_filter.findData(current)
        self.app_filter.setCurrentIndex(max(0, i))
        self.app_filter.blockSignals(False)
        self._reload()

    def _reload(self):
        rows = self.app.history.search(self.search.text(), self.app_filter.currentData(), limit=1000)
        self.model.set_rows(rows)
        searching = bool(self.search.text().strip()) or self.app_filter.currentData()
        if rows:
            self.stack.setCurrentWidget(self.table)
            self.table.selectRow(0)
        else:
            self.empty.set_text("No matches" if searching else "Nothing here yet",
                                "Try other words or another app." if searching
                                else "Your dictations will appear here.")
            self.stack.setCurrentWidget(self.empty)
        self.count.setText(f"{len(rows)} dictation{'s' if len(rows) != 1 else ''}"
                           + (" shown" if searching else ""))
        self._show_detail()

    def _selected(self) -> dict | None:
        idx = self.table.currentIndex()
        if not idx.isValid() or idx.row() >= len(self.model.rows):
            return None
        return self.model.rows[idx.row()]

    def _show_detail(self):
        e = self._selected()
        for b in (self.b_copy, self.b_paste, self.b_delete):
            b.setEnabled(e is not None)
        if e is None:
            self.detail_meta.setText("")
            self.detail_text.setPlainText("")
            self.raw_label.setText("")
            return
        style = f" · {e['style']} style" if e.get("style") else ""
        dur = f" · {e['duration_s']:.0f} s" if e.get("duration_s") else ""
        self.detail_meta.setText(f"{e['ts']} · {pretty_app(e['app'])} · {e['words']} words{dur}{style}")
        self.detail_text.setPlainText(e["text"])
        raw = e.get("raw") or ""
        self.raw_label.setText(f"Heard: {raw[:600]}" if raw and raw != e["text"] else "")
        self.b_paste.setText(f"Paste into {pretty_app(e['app'])}" if e["app"] else "Paste")

    # ---------- actions ----------

    def _copy(self):
        e = self._selected()
        if e:
            QApplication.clipboard().setText(e["text"])
            self.app.flash("Copied")

    def _paste(self):
        e = self._selected()
        if e:
            self.app.paste_into_last_app(e["text"], e["app"])

    def _delete(self):
        e = self._selected()
        if e and self.app.history.delete(e["id"]):
            self._reload()

    def _clear(self):
        box = QMessageBox(QMessageBox.Warning, "Clear history",
                          "Delete every dictation from the history? This can't be undone.",
                          QMessageBox.Yes | QMessageBox.Cancel, self.widget)
        box.setDefaultButton(QMessageBox.Cancel)
        if box.exec() == QMessageBox.Yes:
            self.app.history.clear()
            self.refresh()

    def restyle(self):
        self.search_action.setIcon(icons.icon("search", self.app.theme.tokens["text3"]))
