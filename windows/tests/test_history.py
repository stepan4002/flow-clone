"""History: ids, search, delete, legacy lines, thread safety."""
import json
import threading

from flowclone.history import History


def test_append_search_delete(tmp_path):
    h = History(path=tmp_path / "h.jsonl")
    a = h.append("Hello world", "hello world", "slack.exe", duration_s=1.5, style="casual")
    h.append("Meeting at three", "meeting at three", "outlook.exe")
    assert [e["text"] for e in h.entries()] == ["Meeting at three", "Hello world"]
    assert h.search("HELLO")[0]["id"] == a["id"]
    assert h.search("", app="outlook.exe")[0]["text"] == "Meeting at three"
    assert h.apps() == ["outlook.exe", "slack.exe"]
    assert h.delete(a["id"]) and not h.delete(a["id"])
    assert h.last() == "Meeting at three"
    h.clear()
    assert h.entries() == [] and h.last() is None


def test_legacy_lines_get_stable_ids(tmp_path):
    p = tmp_path / "h.jsonl"
    old = {"ts": "2026-01-01 10:00:00", "app": "x.exe", "raw": "hi", "text": "Hi."}
    p.write_text(json.dumps(old) + "\n" + "not json\n", encoding="utf-8")
    h = History(path=p)
    e1 = h.entries()[0]
    assert e1["id"] == History(path=p).entries()[0]["id"]
    assert e1["words"] == 1 and e1["duration_s"] == 0.0
    assert h.delete(e1["id"]) and h.entries() == []


def test_trim_keeps_limit(tmp_path):
    h = History(limit=3, path=tmp_path / "h.jsonl")
    for i in range(7):
        h.append(f"t{i}", "", "")
    assert len(h.entries()) <= 6 and h.last() == "t6"


def test_concurrent_append_and_read(tmp_path):
    h = History(limit=500, path=tmp_path / "h.jsonl")
    errors = []

    def writer():
        for i in range(100):
            h.append(f"w{i}", "", "a.exe")

    def reader():
        try:
            for _ in range(100):
                h.entries()
        except Exception as e:  # pragma: no cover
            errors.append(e)

    ts = [threading.Thread(target=writer), threading.Thread(target=reader)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert not errors and len(h.entries()) == 100


def test_hand_edited_lines_are_repaired(tmp_path):
    p = tmp_path / "h.jsonl"
    p.write_text('{"text": null}\n{"app": 5, "text": "ok"}\n', encoding="utf-8")
    e = History(path=p).entries()
    assert [x["text"] for x in e] == ["ok", ""] and all(x["ts"] == "" for x in e)


def test_identical_legacy_lines_get_distinct_ids(tmp_path):
    p = tmp_path / "h.jsonl"
    line = json.dumps({"ts": "2026-01-01 10:00:00", "app": "a", "raw": "ok", "text": "ok"})
    p.write_text(line + "\n" + line + "\n", encoding="utf-8")
    h = History(path=p)
    ids = [x["id"] for x in h.entries()]
    assert len(set(ids)) == 2
    assert h.delete(ids[0]) and len(h.entries()) == 1
