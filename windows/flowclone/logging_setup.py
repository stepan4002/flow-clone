"""Rotating file log in %APPDATA%/FlowClone/logs + console output."""
import logging
from logging.handlers import RotatingFileHandler

from .config import APP_DIR

LOG_DIR = APP_DIR / "logs"


def setup_logging() -> logging.Logger:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("flowclone")
    if logger.handlers:
        return logger
    logger.setLevel(logging.INFO)
    fmt = logging.Formatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s")

    fh = RotatingFileHandler(LOG_DIR / "flowclone.log", maxBytes=1_000_000,
                             backupCount=3, encoding="utf-8")
    fh.setFormatter(fmt)
    logger.addHandler(fh)

    ch = logging.StreamHandler()
    ch.setFormatter(logging.Formatter("%(levelname)-7s %(message)s"))
    logger.addHandler(ch)
    return logger
