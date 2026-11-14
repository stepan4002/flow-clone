"""Stats: words, streaks, speed, time saved, CJK counting."""
from datetime import date, timedelta

from flowclone.stats import Stats, count_words


def test_count_words_latin_and_cjk():
    assert count_words("Hello there, world") == 3
    assert count_words("今日は") == 3
    assert count_words("ok 你好") == 3
    assert count_words("") == 0


def test_streak_wpm_time_saved(tmp_path):
    day = [date(2026, 3, 1)]
    s = Stats(path=tmp_path / "s.json", today=lambda: day[0])
    s.add("one two three", seconds=30, app="slack.exe")
    assert s.wpm() is None                      # under a minute recorded
    day[0] += timedelta(days=1)
    s.add(" ".join(["w"] * 117), seconds=30, app="code.exe")
    assert s.streak() == (2, 2)
    assert s.wpm() == 120
    assert s.time_saved_minutes(typing_wpm=40) == 2   # 120/40 - 1
    assert s.top_apps(1) == [("code.exe", 117)]
    day[0] += timedelta(days=1)
    assert s.streak()[0] == 2                    # nothing today yet: still counts
    day[0] += timedelta(days=1)
    assert s.streak() == (0, 2)                  # missed a day
    series = s.days_series(4)
    assert len(series) == 4 and series[0] == ("2026-03-01", 3)


def test_legacy_file_has_no_speed(tmp_path):
    p = tmp_path / "s.json"
    p.write_text('{"total_words": 5000, "total_dictations": 50, "days": {}}', encoding="utf-8")
    s = Stats(path=p)
    s.add("hello", seconds=0)
    assert s.wpm() is None and s.time_saved_minutes() == 0
    assert s.snapshot()["total_words"] == 5001


def test_bad_types_in_stats_file_are_ignored(tmp_path):
    p = tmp_path / "s.json"
    p.write_text('{"days": [], "apps": null, "best_streak": "3", "total_words": 10}',
                 encoding="utf-8")
    s = Stats(path=p)
    s.add("hello world", seconds=2, app="x.exe")
    assert s.snapshot()["total_words"] == 12 and s.streak()[0] == 1
