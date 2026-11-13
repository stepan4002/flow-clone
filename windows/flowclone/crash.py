"""Make every crash visible in the log.

FlowClone.exe has no console, and exceptions in background threads (tray, Hub/overlay,
model loader) would otherwise vanish. Hard native crashes (e.g. inside onnxruntime) are
written to logs/crash.log by faulthandler.
"""
import faulthandler
import logging
import sys
import threading

_crash_file = None  # kept open for the process lifetime (faulthandler writes to it)


def install(log_dir) -> None:
    global _crash_file
    log = logging.getLogger("flowclone")

    def _excepthook(exc_type, exc, tb):
        if issubclass(exc_type, KeyboardInterrupt):
            return
        log.critical("unhandled exception", exc_info=(exc_type, exc, tb))

    def _thread_excepthook(args):
        if args.exc_type is SystemExit:
            return
        name = args.thread.name if args.thread else "?"
        log.critical("unhandled exception in thread %s", name,
                     exc_info=(args.exc_type, args.exc_value, args.exc_traceback))

    sys.excepthook = _excepthook
    threading.excepthook = _thread_excepthook
    try:
        log_dir.mkdir(parents=True, exist_ok=True)
        _crash_file = open(log_dir / "crash.log", "a", encoding="utf-8")
        faulthandler.enable(_crash_file)
    except OSError:
        log.warning("could not enable crash.log")


def show_fatal(message: str) -> None:
    """A message box for errors that stop FlowClone from starting (no console in the exe)."""
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(None, message, "FlowClone", 0x10)  # MB_ICONERROR
    except Exception:  # not on Windows, or no desktop session
        pass
