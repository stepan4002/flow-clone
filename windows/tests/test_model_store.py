"""Model store: pinned, verified, resumable download and HF-cache reuse (no network)."""
import hashlib
import os
import threading

import pytest

from flowclone import model_store as ms


@pytest.fixture
def small_files(monkeypatch):
    """Replace the real ~670 MB file list with tiny files."""
    content = {"a.onnx": b"A" * 5000, "b.txt": b"hello world"}
    files = {n: (len(b), hashlib.sha256(b).hexdigest()) for n, b in content.items()}
    monkeypatch.setattr(ms, "FILES", files)
    monkeypatch.setattr(ms, "TOTAL_BYTES", sum(len(b) for b in content.values()))
    monkeypatch.setattr(ms, "CHUNK", 1000)
    monkeypatch.setattr(ms, "SPARE_BYTES", 0)
    return content


class FakeResponse:
    def __init__(self, status, body=b"", headers=None):
        self.status_code, self.body, self.headers = status, body, headers or {}

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def iter_content(self, n):
        for i in range(0, len(self.body), n):
            yield self.body[i:i + n]


class FakeSession:
    """Serves `content`, honouring Range; can fail after `fail_after` bytes once."""

    def __init__(self, content, fail_after=None, ignore_range=False):
        self.content, self.fail_after, self.ignore_range = content, fail_after, ignore_range
        self.requests = []

    def get(self, url, headers=None, stream=True, timeout=None):
        name = url.rsplit("/", 1)[1]
        self.requests.append((name, dict(headers or {})))
        data = self.content[name]
        rng = (headers or {}).get("Range")
        if rng and not self.ignore_range:
            start = int(rng.split("=")[1].rstrip("-"))
            if start >= len(data):
                return FakeResponse(416)
            return FakeResponse(206, data[start:], {"Content-Range": f"bytes {start}-{len(data)-1}/{len(data)}"})
        body = data
        if self.fail_after is not None:
            n, self.fail_after = self.fail_after, None
            return _Failing(body[:n])
        return FakeResponse(200, body)


class _Failing(FakeResponse):
    def __init__(self, body):
        super().__init__(200, body)

    def iter_content(self, n):
        yield from super().iter_content(n)
        raise ConnectionError("connection reset")


def test_decide_table():
    assert ms.decide(206, 100, 100) == "append"
    assert ms.decide(206, 100, 0) == "restart"
    assert ms.decide(206, 100, 50) == "fail"
    assert ms.decide(206, 100, None) == "fail"
    assert ms.decide(200, 100, None) == "restart"
    assert ms.decide(416, 100, None) == "verify"
    assert ms.decide(416, 0, None) == "fail"
    assert ms.decide(404, 0, None) == "fail"
    assert ms.content_range_start("bytes 1000-2999/3000") == 1000
    assert ms.content_range_start(None) is None


def test_download_verifies_and_marks(tmp_path, small_files):
    seen = []
    ms.download(lambda d, t: seen.append((d, t)), target=tmp_path, session=FakeSession(small_files))
    assert ms.is_complete(tmp_path)
    assert (tmp_path / "a.onnx").read_bytes() == small_files["a.onnx"]
    assert seen[-1] == (ms.TOTAL_BYTES, ms.TOTAL_BYTES)
    assert [d for d, _ in seen] == sorted(d for d, _ in seen)  # monotonic


def test_interrupted_download_resumes_from_part(tmp_path, small_files):
    session = FakeSession(small_files, fail_after=2000)
    with pytest.raises(ms.ModelError):
        ms.download(target=tmp_path, session=session)
    assert (tmp_path / "a.onnx.part").stat().st_size == 2000
    ms.download(target=tmp_path, session=session)
    assert session.requests[-2][1] == {"Range": "bytes=2000-"}  # resumed, not restarted
    assert ms.is_complete(tmp_path)


def test_server_ignoring_range_restarts_cleanly(tmp_path, small_files):
    (tmp_path / "a.onnx.part").write_bytes(b"A" * 1000)
    ms.download(target=tmp_path, session=FakeSession(small_files, ignore_range=True))
    assert (tmp_path / "a.onnx").read_bytes() == small_files["a.onnx"]


def test_damaged_file_is_deleted(tmp_path, small_files):
    bad = dict(small_files, **{"b.txt": b"HELLO WORLD"})  # same size, wrong content
    with pytest.raises(ms.ModelError) as e:
        ms.download(target=tmp_path, session=FakeSession(bad))
    assert "damaged" in str(e.value)
    assert not (tmp_path / "b.txt").exists() and not (tmp_path / "b.txt.part").exists()


def test_cancel_keeps_part(tmp_path, small_files):
    cancel = threading.Event()
    cancel.set()
    with pytest.raises(ms.ModelError):
        ms.download(cancel_event=cancel, target=tmp_path, session=FakeSession(small_files))
    assert not ms.is_complete(tmp_path)


def test_not_enough_disk_space(tmp_path, small_files, monkeypatch):
    monkeypatch.setattr(ms.shutil, "disk_usage", lambda p: type("U", (), {"free": 10})())
    with pytest.raises(ms.ModelError) as e:
        ms.download(target=tmp_path, session=FakeSession(small_files))
    assert "disk space" in e.value.short


def test_seed_hardlinks_from_hf_cache(tmp_path, small_files, monkeypatch):
    snap = tmp_path / "hub" / ("models--" + ms.REPO.replace("/", "--")) / "snapshots" / "abc"
    snap.mkdir(parents=True)
    for n, b in small_files.items():
        (snap / n).write_bytes(b)
    monkeypatch.setenv("HF_HUB_CACHE", str(tmp_path / "hub"))
    target = tmp_path / "model"
    assert ms.seed_from_hf_cache(target)
    assert ms.is_complete(target)
    assert os.path.samefile(target / "a.onnx", snap / "a.onnx")  # hardlink, no extra space


def test_seed_falls_back_to_copy(tmp_path, small_files, monkeypatch):
    snap = tmp_path / "hub" / ("models--" + ms.REPO.replace("/", "--")) / "snapshots" / "abc"
    snap.mkdir(parents=True)
    for n, b in small_files.items():
        (snap / n).write_bytes(b)
    monkeypatch.setenv("HF_HUB_CACHE", str(tmp_path / "hub"))

    def no_link(*a):
        raise OSError("cross-device")

    monkeypatch.setattr(ms.os, "link", no_link)
    target = tmp_path / "model"
    assert ms.seed_from_hf_cache(target)
    assert (target / "b.txt").read_bytes() == small_files["b.txt"]


def test_seed_rejects_wrong_cache_files(tmp_path, small_files, monkeypatch):
    snap = tmp_path / "hub" / ("models--" + ms.REPO.replace("/", "--")) / "snapshots" / "abc"
    snap.mkdir(parents=True)
    for n, b in small_files.items():
        (snap / n).write_bytes(b.upper())
    monkeypatch.setenv("HF_HUB_CACHE", str(tmp_path / "hub"))
    assert not ms.seed_from_hf_cache(tmp_path / "model")


def test_ensure_retries_then_succeeds(tmp_path, small_files, monkeypatch):
    monkeypatch.setattr(ms, "target_dir", lambda: tmp_path / "model")
    monkeypatch.setenv("HF_HUB_CACHE", str(tmp_path / "empty"))
    calls = []
    real_download = ms.download

    def flaky(progress_cb, cancel_event, target):
        calls.append(1)
        if len(calls) < 2:
            raise ms.ModelError("boom", "network error")
        return real_download(progress_cb, cancel_event, target, session=FakeSession(small_files))

    monkeypatch.setattr(ms, "download", flaky)
    assert ms.ensure(sleep=lambda s: None) == tmp_path / "model"
    assert len(calls) == 2


def test_ensure_skips_when_complete(tmp_path, small_files, monkeypatch):
    monkeypatch.setattr(ms, "target_dir", lambda: tmp_path)
    ms.download(target=tmp_path, session=FakeSession(small_files))
    monkeypatch.setattr(ms, "download", lambda *a, **k: pytest.fail("downloaded again"))
    assert ms.ensure() == tmp_path


def test_ensure_verifies_hand_placed_files(tmp_path, small_files, monkeypatch):
    monkeypatch.setattr(ms, "target_dir", lambda: tmp_path)
    for n, b in small_files.items():
        (tmp_path / n).write_bytes(b)
    monkeypatch.setattr(ms, "download", lambda *a, **k: pytest.fail("downloaded again"))
    assert ms.ensure() == tmp_path
    assert ms.is_complete(tmp_path)


def test_progress_is_throttled():
    now = [0.0]
    calls = []
    p = ms._Progress(lambda d, t: calls.append(d), 0, clock=lambda: now[0])
    p.add(10)            # t=0: first call goes through
    p.add(10)            # same instant: throttled
    now[0] = 0.3
    p.add(10)
    assert calls == [10, 30]
