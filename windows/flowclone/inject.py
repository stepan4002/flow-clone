"""Text injection into the focused app — the same trick Wispr Flow uses.

Primary mode 'paste': save clipboard -> put transcript on clipboard -> simulate Ctrl+V ->
restore previous clipboard. Works in ~95% of apps, instant for long text.
Fallback mode 'type': per-character Unicode typing (SendInput) for apps that block paste
(some terminals, RDP sessions) — configure them in injection.type_mode_apps.

Command Mode reads the current selection with Ctrl+C (read_selection) and restores the user's
clipboard right away.

Known quirk (Wispr Flow has it too): only plain-text clipboard content is saved/restored; a
copied image is lost when a dictation fires.
"""
import logging
import sys
import time

import keyboard
import pyperclip

log = logging.getLogger("flowclone")


def insert_text(text: str, mode: str = "paste", restore_delay_ms: int = 300,
                press_enter: bool = False):
    if not text:
        return
    if mode == "type":
        keyboard.write(text)
    else:
        old = None
        try:
            old = pyperclip.paste()
        except pyperclip.PyperclipException:
            pass  # non-text clipboard; nothing we can restore
        pyperclip.copy(text)
        time.sleep(0.06)          # let the clipboard settle before the paste lands
        keyboard.send("ctrl+v")
        time.sleep(max(restore_delay_ms, 50) / 1000)
        if old is not None:
            try:
                pyperclip.copy(old)
            except pyperclip.PyperclipException:
                pass
    if press_enter:
        time.sleep(0.08)
        keyboard.send("enter")


def copy_to_clipboard(text: str):
    try:
        pyperclip.copy(text)
    except pyperclip.PyperclipException as e:
        log.warning("clipboard copy failed: %s", e)


def _clipboard_seq() -> int:
    if sys.platform != "win32":
        return 0
    import ctypes
    return ctypes.windll.user32.GetClipboardSequenceNumber()


# Terminals: Ctrl+C with nothing selected interrupts the running program. Never send it there.
TERMINALS = {"windowsterminal.exe", "wt.exe", "cmd.exe", "powershell.exe", "pwsh.exe",
             "conhost.exe", "openconsole.exe", "mintty.exe", "alacritty.exe", "wezterm-gui.exe",
             "putty.exe", "kitty.exe", "tabby.exe", "hyper.exe", "warp.exe"}
# Editors that copy the whole current line when nothing is selected.
LINE_COPY_EDITORS = {"code.exe", "code - insiders.exe", "cursor.exe", "windsurf.exe",
                     "vscodium.exe", "devenv.exe", "sublime_text.exe", "notepad++.exe",
                     "idea64.exe", "pycharm64.exe", "webstorm64.exe", "rider64.exe",
                     "clion64.exe", "goland64.exe", "phpstorm64.exe", "rubymine64.exe",
                     "datagrip64.exe", "studio64.exe", "fleet.exe", "zed.exe"}


def can_read_selection(app: str) -> bool:
    """False where Ctrl+C is not a harmless copy (terminals)."""
    return (app or "").lower() not in TERMINALS


def _looks_like_line_copy(text: str) -> bool:
    """An editor's 'copy line' result: exactly one line plus its line break."""
    body = text.rstrip("\r\n")
    return text != body and "\n" not in body


def read_selection(app: str = "", timeout_s: float = 0.35) -> str | None:
    """The text selected in the focused app, or None when nothing is selected.

    Sends Ctrl+C and watches the clipboard sequence number (not its content: the selection may
    equal what was already on the clipboard). The user's clipboard is restored immediately.
    In editors that copy the current line when nothing is selected, a single whole line with its
    line break counts as "nothing selected" (so that line is not edited or sent anywhere).
    """
    try:
        old = pyperclip.paste()
    except pyperclip.PyperclipException:
        old = None
    before = _clipboard_seq()
    keyboard.send("ctrl+c")
    deadline = time.monotonic() + timeout_s
    changed = False
    while time.monotonic() < deadline:
        time.sleep(0.02)
        if _clipboard_seq() != before:
            changed = True
            time.sleep(0.03)  # let the app finish writing all formats
            break
    selection = None
    if changed:
        try:
            selection = pyperclip.paste()
        except pyperclip.PyperclipException:
            selection = None
    if old is not None:
        try:
            pyperclip.copy(old)
        except pyperclip.PyperclipException:
            pass
    if not selection or not selection.strip():
        return None
    if (app or "").lower() in LINE_COPY_EDITORS and _looks_like_line_copy(selection):
        return None
    return selection


_MODIFIER_VKS = (0x10, 0x11, 0x12, 0x5B, 0x5C)  # Shift, Ctrl, Alt, left/right Win


def modifier_down(vk: int) -> bool:
    """Physical key state from Windows (also right after focus moved to an admin window)."""
    if sys.platform != "win32":
        return False
    import ctypes
    return bool(ctypes.windll.user32.GetAsyncKeyState(vk) & 0x8000)


def wait_modifiers_released(timeout_s: float = 1.0):
    """Wait until Shift/Ctrl/Alt/Win are up (so a paste isn't Ctrl+Shift+Alt+V)."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline and any(modifier_down(vk) for vk in _MODIFIER_VKS):
        time.sleep(0.02)


def find_app_window(exe: str) -> int:
    """A visible top-level window of a running app (e.g. 'slack.exe'), or 0."""
    if sys.platform != "win32" or not exe:
        return 0
    try:
        import psutil
        import win32gui
        import win32process
        found = []

        def cb(hwnd, _):
            if found or not win32gui.IsWindowVisible(hwnd) or not win32gui.GetWindowText(hwnd):
                return True
            try:
                _, pid = win32process.GetWindowThreadProcessId(hwnd)
                if psutil.Process(pid).name().lower() == exe.lower():
                    found.append(hwnd)
            except Exception:
                pass
            return True
        win32gui.EnumWindows(cb, None)
        return found[0] if found else 0
    except Exception:
        return 0


def foreground_window() -> int:
    if sys.platform != "win32":
        return 0
    import ctypes
    return ctypes.windll.user32.GetForegroundWindow()


def focus_window(hwnd: int) -> bool:
    """Bring a window back to the front (e.g. to paste a history entry into it)."""
    if sys.platform != "win32" or not hwnd:
        return False
    try:
        import ctypes
        user32 = ctypes.windll.user32
        if not user32.IsWindow(hwnd):
            return False
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        fg = user32.GetForegroundWindow()
        fg_thread = user32.GetWindowThreadProcessId(fg, None)
        me = ctypes.windll.kernel32.GetCurrentThreadId()
        if fg_thread and fg_thread != me:
            user32.AttachThreadInput(fg_thread, me, True)
        ok = bool(user32.SetForegroundWindow(hwnd))
        if fg_thread and fg_thread != me:
            user32.AttachThreadInput(fg_thread, me, False)
        return ok
    except Exception:
        return False


def foreground_is_elevated() -> bool:
    """True when the focused window belongs to an admin process and FlowClone isn't admin:
    Windows then silently blocks simulated keys (UIPI)."""
    if sys.platform != "win32":
        return False
    import ctypes
    from ctypes import wintypes
    k32, adv = ctypes.windll.kernel32, ctypes.windll.advapi32

    def elevated(handle) -> bool | None:
        token = wintypes.HANDLE()
        if not adv.OpenProcessToken(handle, 0x0008, ctypes.byref(token)):  # TOKEN_QUERY
            return None
        try:
            value, size = wintypes.DWORD(), wintypes.DWORD()
            ok = adv.GetTokenInformation(token, 20, ctypes.byref(value), 4, ctypes.byref(size))
            return bool(value.value) if ok else None
        finally:
            k32.CloseHandle(token)

    try:
        if elevated(k32.GetCurrentProcess()):
            return False
        pid = wintypes.DWORD()
        ctypes.windll.user32.GetWindowThreadProcessId(foreground_window(), ctypes.byref(pid))
        if not pid.value:
            return False
        proc = k32.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not proc:
            return False
        try:
            result = elevated(proc)
            return True if result is None else result  # can't read an admin token: assume admin
        finally:
            k32.CloseHandle(proc)
    except Exception:
        return False
