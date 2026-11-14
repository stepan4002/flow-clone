"""Line icons from assets/icons/*.svg, recoloured to the theme at runtime."""
from functools import lru_cache

from PySide6.QtCore import QByteArray, QRectF, Qt
from PySide6.QtGui import QIcon, QPainter, QPixmap
from PySide6.QtSvg import QSvgRenderer

from .. import paths


@lru_cache(maxsize=None)
def _svg(name: str) -> str:
    return paths.resource(f"assets/icons/{name}.svg").read_text(encoding="utf-8")


@lru_cache(maxsize=512)
def pixmap(name: str, color: str, size: int = 20, dpr: float = 2.0) -> QPixmap:
    renderer = QSvgRenderer(QByteArray(_svg(name).replace("currentColor", color).encode("utf-8")))
    px = QPixmap(int(size * dpr), int(size * dpr))
    px.fill(Qt.transparent)
    p = QPainter(px)
    renderer.render(p, QRectF(0, 0, size * dpr, size * dpr))
    p.end()
    px.setDevicePixelRatio(dpr)
    return px


@lru_cache(maxsize=512)
def icon(name: str, color: str) -> QIcon:
    ic = QIcon()
    for size in (16, 20, 24, 32):
        ic.addPixmap(pixmap(name, color, size, 2.0))
    return ic


def clear_cache():
    pixmap.cache_clear()
    icon.cache_clear()
