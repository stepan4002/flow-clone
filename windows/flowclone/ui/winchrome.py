"""Windows 11 window chrome: dark/light title bar matching the theme, rounded corners."""
import logging
import sys

log = logging.getLogger("flowclone")

_DWMWA_USE_IMMERSIVE_DARK_MODE = 20
_DWMWA_WINDOW_CORNER_PREFERENCE = 33
_DWMWA_CAPTION_COLOR = 35
_DWMWCP_ROUND = 2


def apply_titlebar(widget, dark: bool, caption_hex: str | None = None):
    if sys.platform != "win32":
        return
    try:
        import ctypes
        hwnd = int(widget.winId())
        dwm = ctypes.windll.dwmapi
        val = ctypes.c_int(1 if dark else 0)
        dwm.DwmSetWindowAttribute(hwnd, _DWMWA_USE_IMMERSIVE_DARK_MODE, ctypes.byref(val), 4)
        corner = ctypes.c_int(_DWMWCP_ROUND)
        dwm.DwmSetWindowAttribute(hwnd, _DWMWA_WINDOW_CORNER_PREFERENCE, ctypes.byref(corner), 4)
        if caption_hex:
            h = caption_hex.lstrip("#")
            r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
            colorref = ctypes.c_uint(r | (g << 8) | (b << 16))
            dwm.DwmSetWindowAttribute(hwnd, _DWMWA_CAPTION_COLOR, ctypes.byref(colorref), 4)
    except Exception:
        log.debug("title bar styling not applied", exc_info=True)


def force_foreground(widget):
    """Bring a window to the front even when another app has the foreground (Windows only
    lets the foreground process do that; AttachThreadInput is the usual workaround)."""
    if widget.isMaximized():
        widget.showMaximized()
    else:
        widget.showNormal()
    widget.raise_()
    widget.activateWindow()
    if sys.platform != "win32":
        return
    try:
        import ctypes
        user32 = ctypes.windll.user32
        hwnd = int(widget.winId())
        fg = user32.GetForegroundWindow()
        if fg == hwnd:
            return
        fg_thread = user32.GetWindowThreadProcessId(fg, None)
        me = ctypes.windll.kernel32.GetCurrentThreadId()
        if fg_thread and fg_thread != me:
            user32.AttachThreadInput(fg_thread, me, True)
            user32.SetForegroundWindow(hwnd)
            user32.BringWindowToTop(hwnd)
            user32.AttachThreadInput(fg_thread, me, False)
        else:
            user32.SetForegroundWindow(hwnd)
    except Exception:
        log.debug("could not force the window to the front", exc_info=True)
