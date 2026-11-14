"""About: version, privacy, credits, links."""
import os
import webbrowser

from PySide6.QtCore import Qt
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import QHBoxLayout, QLabel, QVBoxLayout

from ... import __version__, paths
from ...config import APP_DIR
from ..widgets import Card, button, label, page

REPO = "https://github.com/stepan4002/flow-clone"


class AboutPage:
    name = "about"

    def __init__(self, app):
        t = app.theme
        self.widget, col = page("About")
        head = Card()
        r = QHBoxLayout()
        logo = QLabel()
        png = paths.resource("assets/flowclone.png")
        if png.exists():
            logo.setPixmap(QPixmap(str(png)).scaled(64, 64, Qt.KeepAspectRatio,
                                                    Qt.SmoothTransformation))
        r.addWidget(logo)
        txt = QVBoxLayout()
        txt.addWidget(label("FlowClone", "h1"))
        txt.addWidget(label(f"Version {__version__} · free and open source (MIT)", "muted"))
        r.addLayout(txt, 1)
        head.body.addLayout(r)
        links = QHBoxLayout()
        for text, icon, url in (("GitHub", "external", REPO),
                                ("Report a problem", "external", REPO + "/issues"),
                                ("What's new", "external", REPO + "/releases")):
            b = button(text, None, icon, t)
            b.clicked.connect(lambda _=False, u=url: webbrowser.open(u))
            links.addWidget(b)
        links.addStretch()
        head.body.addLayout(links)
        col.addWidget(head)

        priv = Card("Privacy")
        priv.body.addWidget(label(
            "Speech recognition runs entirely on this computer: your voice is never uploaded. "
            "Your history, dictionary and settings stay in your Windows profile. FlowClone has "
            "no account, no telemetry and no analytics.\n\n"
            "The only network use: the one-time speech-model download from Hugging Face, and — "
            "only if you turn on AI rewrite with a cloud provider — the text of your dictations "
            "is sent to that provider with your own API key.", wrap=True))
        col.addWidget(priv)

        cred = Card("Credits")
        cred.body.addWidget(label(
            "Speech model: NVIDIA Parakeet TDT 0.6B v3 (CC-BY-4.0), ONNX export by istupakov, run "
            "with onnx-asr and ONNX Runtime.\nUser interface: Qt 6 / PySide6 (LGPL-3.0).\n"
            "Full licence texts are in THIRD-PARTY-NOTICES.txt.", "muted", wrap=True))
        row = QHBoxLayout()
        notices = paths.resource("THIRD-PARTY-NOTICES.txt")
        nb = button("Third-party notices", None, "external", t)
        nb.setEnabled(notices.exists())
        nb.clicked.connect(lambda: os.startfile(str(notices)))
        row.addWidget(nb)
        fb = button("Settings folder", "ghost", "folder", t)
        fb.clicked.connect(lambda: os.startfile(str(APP_DIR)))
        row.addWidget(fb)
        lb = button("Log files", "ghost", "folder", t)
        lb.clicked.connect(lambda: os.startfile(str(APP_DIR / "logs"))
                           if (APP_DIR / "logs").exists() else None)
        row.addWidget(lb)
        row.addStretch()
        cred.body.addLayout(row)
        col.addWidget(cred)
        col.addStretch()

    def refresh(self):
        pass
