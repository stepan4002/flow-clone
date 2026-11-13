"""Which app is focused? Used for tone matching and per-app injection mode."""
import psutil
import win32gui
import win32process


def get_foreground_app() -> str:
    """Returns the focused window's process name, lowercase (e.g. 'slack.exe')."""
    try:
        hwnd = win32gui.GetForegroundWindow()
        _tid, pid = win32process.GetWindowThreadProcessId(hwnd)
        return psutil.Process(pid).name().lower()
    except (psutil.Error, OSError, ValueError):
        return ""
