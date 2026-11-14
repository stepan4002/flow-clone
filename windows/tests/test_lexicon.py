"""Tests for the personal dictionary / snippets layer (flowclone/lexicon.py)."""
import json

from flowclone import lexicon


def _use_tmp_files(monkeypatch, tmp_path):
    monkeypatch.setattr(lexicon, "DICTIONARY_PATH", tmp_path / "dictionary.json")
    monkeypatch.setattr(lexicon, "SNIPPETS_PATH", tmp_path / "snippets.json")


def test_first_run_writes_example_files(monkeypatch, tmp_path):
    _use_tmp_files(monkeypatch, tmp_path)
    lexicon.Lexicon()
    assert (tmp_path / "dictionary.json").exists()
    assert (tmp_path / "snippets.json").exists()


def test_default_snippets_contain_no_personal_data():
    for value in lexicon._DEFAULT_SNIPPETS.values():
        assert "@" not in value or value.endswith("@example.com")


def test_corrections_replacements_snippets_and_casing(monkeypatch, tmp_path):
    _use_tmp_files(monkeypatch, tmp_path)
    (tmp_path / "dictionary.json").write_text(json.dumps({
        "words": ["GitHub"],
        "corrections": {"Eric": "Erik"},
        "replacements": {"brb": "be right back"},
    }), encoding="utf-8")
    (tmp_path / "snippets.json").write_text(json.dumps({
        "insert my email": "me@example.com",
    }), encoding="utf-8")

    lex = lexicon.Lexicon()
    out = lex.apply("tell eric brb and insert my email on github")
    assert out == "tell Erik be right back and me@example.com on GitHub"


def test_broken_json_falls_back_to_defaults(monkeypatch, tmp_path):
    _use_tmp_files(monkeypatch, tmp_path)
    (tmp_path / "dictionary.json").write_text("{not json", encoding="utf-8")
    (tmp_path / "snippets.json").write_text("{}", encoding="utf-8")
    lex = lexicon.Lexicon()
    assert lex.words == lexicon._DEFAULT_DICTIONARY["words"]


def test_save_and_reload_if_changed(monkeypatch, tmp_path):
    import os
    import time
    _use_tmp_files(monkeypatch, tmp_path)
    lex = lexicon.Lexicon()
    lex.save_dictionary(["Kubernetes"], {"cube": "kube"}, {})
    lex.save_snippets({"my zoom": "https://zoom.example/123"})
    assert lex.words == ["Kubernetes"] and lex.snippets == {"my zoom": "https://zoom.example/123"}
    assert not lex.reload_if_changed()
    (tmp_path / "snippets.json").write_text('{"sig": "Cheers"}', encoding="utf-8")
    later = time.time() + 5
    os.utime(tmp_path / "snippets.json", (later, later))
    assert lex.reload_if_changed() and lex.snippets == {"sig": "Cheers"}


def test_wrong_types_in_files_do_not_break_dictation(monkeypatch, tmp_path):
    _use_tmp_files(monkeypatch, tmp_path)
    (tmp_path / "dictionary.json").write_text('{"words": [1, "Kubernetes"], "corrections": null,'
                                              ' "replacements": {"a": 5, "brb": "be right back"}}',
                                              encoding="utf-8")
    (tmp_path / "snippets.json").write_text('["a"]', encoding="utf-8")
    lex = lexicon.Lexicon()
    assert lex.words == ["Kubernetes"] and lex.corrections == {} and lex.snippets == {}
    assert lex.apply("brb kubernetes") == "be right back Kubernetes"
