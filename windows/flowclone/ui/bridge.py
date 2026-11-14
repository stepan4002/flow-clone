"""The only way other threads reach the UI: emit a signal; Qt delivers it on the UI thread."""
from PySide6.QtCore import QObject, Signal


class Bridge(QObject):
    pill = Signal(object)            # tuple emitted by PillController: ("state", ...) etc.
    call = Signal(object)            # a callable to run on the UI thread
    tray_active = Signal(bool)
    tray_tooltip = Signal(str)
    tray_refresh = Signal()
    show_window = Signal(str)        # page name
    notify = Signal(str, str)        # title, message (tray balloon)
    model_status = Signal(str, int)  # loading | downloading | ready | error, percent
    dictation_done = Signal()        # history/stats changed: refresh visible pages

    def __init__(self):
        super().__init__()
        self.call.connect(lambda fn: fn())

    def emit_pill(self, *event):
        """PillController's emit callback (any thread)."""
        self.pill.emit(event)
