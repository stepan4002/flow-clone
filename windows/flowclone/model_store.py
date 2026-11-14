"""The Parakeet speech model on disk: find it, reuse it, or download it — verified.

The model is pinned to one Hugging Face commit and every file is checked by size and
SHA-256, like FlowKey's model download on Android. Downloads resume from `.part` files
(HTTP Range), report real byte progress, and survive restarts. A model that is already in
the user's Hugging Face cache (older FlowClone versions put it there) is hardlinked instead
of downloaded again, so it costs no extra disk space.

Never import heavy or Windows-only modules at module level here: the tests run on Linux CI.
"""
import hashlib
import logging
import os
import shutil
import time
from pathlib import Path

from . import paths

log = logging.getLogger("flowclone")

REPO = "istupakov/parakeet-tdt-0.6b-v3-onnx"
REVISION = "8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce"
# name -> (size in bytes, sha256)
FILES = {
    "encoder-model.int8.onnx": (652_183_999, "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09"),
    "decoder_joint-model.int8.onnx": (18_202_004, "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70"),
    "vocab.txt": (93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    "config.json": (97, "666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466"),
}
TOTAL_BYTES = sum(size for size, _ in FILES.values())
MARKER = ".verified"
SPARE_BYTES = 100 * 1024 * 1024
CHUNK = 1024 * 1024
PROGRESS_INTERVAL_S = 0.25


class ModelError(Exception):
    """A user-facing problem; `short` fits on the status pill."""

    def __init__(self, message: str, short: str | None = None):
        super().__init__(message)
        self.short = (short or message)[:40]


def target_dir() -> Path:
    return paths.models_dir() / "parakeet-tdt-0.6b-v3-int8"


def url(name: str) -> str:
    return f"https://huggingface.co/{REPO}/resolve/{REVISION}/{name}"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def is_complete(target: Path | None = None) -> bool:
    """All files present with the right size, and verified once (marker written after hashing)."""
    target = target or target_dir()
    if not (target / MARKER).is_file():
        return False
    return all((target / n).is_file() and (target / n).stat().st_size == size
               for n, (size, _) in FILES.items())


def _verify_and_mark(target: Path, known_good: frozenset = frozenset()) -> bool:
    """Hash every file (except `known_good` ones, hashed moments ago); delete any that
    don't match, so they are fetched again."""
    ok = True
    for name, (size, digest) in FILES.items():
        f = target / name
        if not f.is_file() or f.stat().st_size != size or (
                name not in known_good and sha256_of(f) != digest):
            ok = False
            if f.exists():
                log.warning("%s does not match the expected model file; removing it", name)
                f.unlink()
    if ok:
        (target / MARKER).write_text(REVISION, encoding="utf-8")
    return ok


def invalidate(target: Path | None = None) -> None:
    """Forget that the folder was verified (e.g. the model failed to load): the next
    ensure() hashes every file again and replaces the damaged ones."""
    (target or target_dir()).joinpath(MARKER).unlink(missing_ok=True)


# ---------------------------------------------------------------- seeding from the HF cache

def hf_cache_root() -> Path:
    if os.environ.get("HF_HUB_CACHE"):
        return Path(os.environ["HF_HUB_CACHE"])
    if os.environ.get("HF_HOME"):
        return Path(os.environ["HF_HOME"]) / "hub"
    return Path.home() / ".cache" / "huggingface" / "hub"


def _cached_candidates(name: str) -> list[Path]:
    """Cache files with the right name and size (not hashed yet)."""
    snapshots = hf_cache_root() / ("models--" + REPO.replace("/", "--")) / "snapshots"
    if not snapshots.is_dir():
        return []
    size, _ = FILES[name]
    found = []
    for snap in sorted(snapshots.iterdir()):
        candidate = snap / name
        if not candidate.exists():
            continue
        real = Path(os.path.realpath(candidate))  # cache snapshots are symlinks into blobs
        try:
            if real.stat().st_size == size:
                found.append(real)
        except OSError:
            continue
    return found


def _cached_file(name: str) -> Path | None:
    _, digest = FILES[name]
    for real in _cached_candidates(name):
        try:
            if sha256_of(real) == digest:
                return real
        except OSError:
            continue
    return None


def seed_from_hf_cache(target: Path | None = None) -> bool:
    """Reuse verified files from the Hugging Face cache (hardlink; copy if that fails)."""
    target = target or target_dir()
    if not all(_cached_candidates(name) for name in FILES):
        return False  # incomplete cache: don't hash 650 MB for nothing
    found = {}
    for name in FILES:
        real = _cached_file(name)
        if real is None:
            return False
        found[name] = real
    target.mkdir(parents=True, exist_ok=True)
    for name, real in found.items():
        tmp = target / (name + ".seed")
        tmp.unlink(missing_ok=True)
        try:
            try:
                os.link(real, tmp)
            except OSError:
                shutil.copy2(real, tmp)
            os.replace(tmp, target / name)
        except OSError:
            tmp.unlink(missing_ok=True)
            raise
    (target / MARKER).write_text(REVISION, encoding="utf-8")
    log.info("speech model reused from the Hugging Face cache (%s)", hf_cache_root())
    return True


# ---------------------------------------------------------------- download

def decide(code: int, part_len: int, range_start: int | None) -> str:
    """What to do with an HTTP response when resuming `part_len` bytes:
    'append', 'restart' (write from byte 0), 'verify' (nothing left to send) or 'fail'."""
    if code == 206:
        if part_len > 0 and range_start == part_len:
            return "append"
        if range_start == 0:
            return "restart"
        return "fail"
    if code == 200:
        return "restart"
    if code == 416:
        return "verify" if part_len > 0 else "fail"
    return "fail"


def content_range_start(header: str | None) -> int | None:
    """'bytes 1000-2999/3000' -> 1000."""
    if not header:
        return None
    try:
        return int(header.split()[1].split("-")[0])
    except (IndexError, ValueError):
        return None


class _Progress:
    def __init__(self, callback, done: int, clock=time.monotonic):
        self.callback, self.done, self.clock = callback, done, clock
        self._last = float("-inf")  # the first update is always reported

    def add(self, n: int, force: bool = False):
        self.done += n
        now = self.clock()
        if self.callback and (force or now - self._last >= PROGRESS_INTERVAL_S):
            self._last = now
            self.callback(min(self.done, TOTAL_BYTES), TOTAL_BYTES)


def _fetch(name: str, target: Path, progress: _Progress, cancel_event, session) -> None:
    size, digest = FILES[name]
    final = target / name
    part = target / (name + ".part")
    part_len = part.stat().st_size if part.exists() else 0
    if part_len > size:
        part.unlink()
        progress.add(-size)  # download() counted min(part_len, size)
        part_len = 0
    if part_len < size:
        headers = {"Range": f"bytes={part_len}-"} if part_len else {}
        with session.get(url(name), headers=headers, stream=True, timeout=(15, 60)) as r:
            action = decide(r.status_code, part_len, content_range_start(r.headers.get("Content-Range")))
            if action == "fail":
                if r.status_code == 206:
                    part.unlink(missing_ok=True)  # wrong offset: start clean next time
                raise ModelError(f"download of {name} failed: HTTP {r.status_code}",
                                 f"HTTP {r.status_code}")
            if action == "restart":
                progress.add(-part_len)
                part_len = 0
            if action != "verify":
                with open(part, "ab" if action == "append" else "wb") as out:
                    for block in r.iter_content(CHUNK):
                        if cancel_event is not None and cancel_event.is_set():
                            raise ModelError("download cancelled", "cancelled")
                        out.write(block)
                        progress.add(len(block))
    if part.stat().st_size != size or sha256_of(part) != digest:
        part.unlink(missing_ok=True)
        raise ModelError(f"{name} was damaged in transit and was deleted; retrying will "
                         "download it again", "file damaged — retry")
    if final.exists():
        final.unlink()
    os.replace(part, final)


def download(progress_cb=None, cancel_event=None, target: Path | None = None, session=None) -> Path:
    target = target or target_dir()
    target.mkdir(parents=True, exist_ok=True)
    have = 0
    remaining = 0
    for name, (size, _) in FILES.items():
        final, part = target / name, target / (name + ".part")
        if final.is_file() and final.stat().st_size == size:
            have += size
        else:
            got = part.stat().st_size if part.exists() else 0
            have += min(got, size)
            remaining += size - min(got, size)
    free = shutil.disk_usage(target).free
    if free < remaining + SPARE_BYTES:
        need_mb = (remaining + SPARE_BYTES) // 1_000_000
        raise ModelError(f"Not enough disk space for the speech model: {need_mb} MB needed",
                         f"not enough disk space ({need_mb} MB)")
    own_session = session is None
    if own_session:
        # See run.py: an antivirus-set SSLKEYLOGFILE makes Python's OpenSSL abort on Windows.
        os.environ.pop("SSLKEYLOGFILE", None)
        import requests  # deferred (tests inject a fake session)
        session = requests.Session()
    try:
        progress = _Progress(progress_cb, have)
        progress.add(0, force=True)
        fetched = set()
        for name, (size, _) in FILES.items():
            final = target / name
            if final.is_file() and final.stat().st_size == size:
                continue
            try:
                _fetch(name, target, progress, cancel_event, session)
                fetched.add(name)
            except ModelError:
                raise
            except Exception as e:  # keep the .part: the next attempt resumes
                raise ModelError(f"download of {name} failed: {e}", _short_reason(e)) from e
        if not _verify_and_mark(target, frozenset(fetched)):
            raise ModelError("downloaded model failed verification", "verification failed")
        progress.add(0, force=True)
        return target
    finally:
        if own_session:
            session.close()


def _short_reason(e: Exception) -> str:
    """Network problems vs. local file problems (requests' errors are OSErrors too)."""
    import errno
    if isinstance(e, OSError) and not type(e).__module__.startswith(("requests", "urllib3")):
        if e.errno == errno.ENOSPC:
            return "disk full"
        if isinstance(e, PermissionError):
            return "model file locked (antivirus?)"
        if getattr(e, "filename", None):  # a file operation, not a socket
            return "could not save the model files"
    return _short_network_reason(e)


def _short_network_reason(e: Exception) -> str:
    text = f"{type(e).__name__} {e}".lower()
    if "ssl" in text or "certificate" in text:
        return "secure connection refused"
    if "timed out" in text or "timeout" in text:
        return "connection timed out"
    if "name resolution" in text or "getaddrinfo" in text or "connection" in text:
        return "no internet connection"
    return "network error"


def ensure(progress_cb=None, cancel_event=None, attempts: int = 3, sleep=time.sleep) -> Path:
    """Return a verified model folder: already there, reused from the HF cache, or downloaded
    (resuming between attempts). Raises ModelError with a short reason on failure."""
    target = target_dir()
    if is_complete(target):
        return target
    if not (target / MARKER).exists() and all((target / n).is_file() for n in FILES):
        # Files placed by hand (offline install): verify once.
        if _verify_and_mark(target):
            return target
    try:
        if seed_from_hf_cache(target):
            return target
    except OSError as e:
        log.warning("could not reuse the Hugging Face cache: %s", e)
    delays = [5, 15, 30]
    last: ModelError | None = None
    for attempt in range(attempts):
        try:
            return download(progress_cb, cancel_event, target)
        except ModelError as e:
            last = e
            if cancel_event is not None and cancel_event.is_set():
                break
            log.warning("model download attempt %d failed: %s", attempt + 1, e)
            if attempt + 1 < attempts:
                sleep(delays[min(attempt, len(delays) - 1)])
    raise last or ModelError("model download failed")
