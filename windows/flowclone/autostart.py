"""Start FlowClone when Windows starts (HKCU Run key), shared by the exe and the script install."""
import logging

from . import paths

log = logging.getLogger("flowclone")

_AUTOSTART_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_AUTOSTART_NAME = "FlowClone"


def _autostart_value() -> str | None:
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _AUTOSTART_KEY) as k:
            value, _type = winreg.QueryValueEx(k, _AUTOSTART_NAME)
        return str(value)
    except OSError:
        return None


def is_autostart_enabled() -> bool:
    """True only if the Run value starts THIS FlowClone (not another install)."""
    value = _autostart_value()
    return value is not None and value.strip().lower() == paths.launch_command().lower()


def _command_target(command: str) -> str:
    """The executable path of a Run-key command line ("C:\\x\\y.exe" "arg" -> C:\\x\\y.exe)."""
    command = command.strip()
    if command.startswith('"'):
        return command[1:].split('"', 1)[0]
    return command.split(" ", 1)[0]


def _command_files(command: str) -> list[str]:
    """Files a Run-key command needs: the program and, for python(w).exe, its script."""
    import os
    import shlex
    command = os.path.expandvars(command.strip())
    if os.path.exists(command.strip('"')):  # a bare (possibly unquoted) program path
        return [command.strip('"')]
    try:
        parts = [p.strip('"') for p in shlex.split(command, posix=False)]
    except ValueError:
        parts = [_command_target(command)]
    files = parts[:1]
    if files and os.path.basename(files[0]).lower().startswith("python") and len(parts) > 1:
        files.append(parts[1])
    return files


def ensure_autostart_current() -> None:
    """Repair a Run value left pointing at a FlowClone that no longer exists (e.g. after
    moving from the script install to FlowClone.exe). Another existing install is left alone."""
    import os
    value = _autostart_value()
    if value is None or value.strip().lower() == paths.launch_command().lower():
        return
    files = _command_files(value)
    if files and all(os.path.exists(f) for f in files):
        log.info("autostart points to another FlowClone install (%s); leaving it", value)
        return
    try:
        set_autostart(True)
        log.info("autostart repaired: %s -> %s", value, paths.launch_command())
    except OSError as e:
        log.warning("could not repair autostart: %s", e)


def set_autostart(enabled: bool):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _AUTOSTART_KEY, 0,
                        winreg.KEY_SET_VALUE) as k:
        if enabled:
            winreg.SetValueEx(k, _AUTOSTART_NAME, 0, winreg.REG_SZ, paths.launch_command())
        else:
            try:
                winreg.DeleteValue(k, _AUTOSTART_NAME)
            except OSError:
                pass
