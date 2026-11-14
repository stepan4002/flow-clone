"""AI rewrite client: providers, guardrails, privacy (no network)."""
import logging

from flowclone.llm import Polisher

SECRET = "sk-test-SECRET-123"
TEXT = "um so we should meet tomorrow at three"


class Resp:
    def __init__(self, status=200, data=None):
        self.status_code, self._data = status, data or {}
        self.ok = 200 <= status < 300

    def json(self):
        return self._data


class Session:
    def __init__(self, *responses):
        self.responses, self.calls = list(responses), []

    def get(self, url, timeout=None):
        self.calls.append(("GET", url, None, None))
        return Resp(200)

    def post(self, url, headers=None, json=None, timeout=None):
        self.calls.append(("POST", url, headers, json))
        r = self.responses.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


def make(provider, *responses, key=SECRET, **cfg):
    s = Session(*responses)
    p = Polisher(dict({"provider": provider}, **cfg), session=s, get_key=lambda prov: key)
    return p, s


def test_ollama_chat_and_think_strip():
    p, s = make("ollama", Resp(200, {"message": {"content": "<think>x</think>We meet at 3."}}))
    assert p.rewrite(TEXT, "Be clean.") == "We meet at 3."
    _, url, _, body = s.calls[-1]
    assert url.endswith("/api/chat") and body["think"] is False and body["stream"] is False
    assert "<transcript>" in body["messages"][1]["content"]


def test_ollama_retries_without_think_on_400():
    p, s = make("ollama", Resp(400), Resp(200, {"message": {"content": "Ok."}}))
    assert p.rewrite("ok", "x") == "Ok."
    assert "think" not in s.calls[-1][3]


def test_openai_auth_and_cutoff():
    p, s = make("openai", Resp(200, {"choices": [{"message": {"content": "Done."},
                                                   "finish_reason": "stop"}]}))
    assert p.rewrite(TEXT, "x") == "Done."
    _, url, headers, body = s.calls[-1]
    assert url == "https://api.openai.com/v1/chat/completions"
    assert headers["Authorization"] == f"Bearer {SECRET}" and body["model"] == "gpt-4o-mini"
    p, _ = make("openai", Resp(200, {"choices": [{"message": {"content": "Do"},
                                                  "finish_reason": "length"}]}))
    assert p.rewrite(TEXT, "x") is None


def test_openai_local_server_needs_no_key_and_rejects_remote_http():
    p, s = make("openai", Resp(200, {"choices": [{"message": {"content": "Hi."}}]}),
                key=None, api_base="http://localhost:1234/v1", api_model="local")
    assert p.rewrite("hi", "x") == "Hi."
    assert "Authorization" not in s.calls[-1][2]
    p, s = make("openai", key=SECRET, api_base="http://example.com/v1")
    assert p.rewrite("hi", "x") is None and s.calls == []


def test_anthropic_shape_and_max_tokens_stop():
    p, s = make("anthropic", Resp(200, {"content": [{"type": "text", "text": "Fine."}],
                                        "stop_reason": "end_turn"}))
    assert p.rewrite(TEXT, "x") == "Fine."
    _, url, headers, body = s.calls[-1]
    assert headers["x-api-key"] == SECRET and headers["anthropic-version"] == "2023-06-01"
    assert body["system"] and body["messages"][0]["role"] == "user" and body["max_tokens"] >= 512
    p, _ = make("anthropic", Resp(200, {"content": [{"type": "text", "text": "Fi"}],
                                        "stop_reason": "max_tokens"}))
    assert p.rewrite(TEXT, "x") is None


def test_errors_and_oversize_return_none():
    p, _ = make("openai", ConnectionError("down"))
    assert p.rewrite(TEXT, "x") is None
    p, _ = make("openai", Resp(401))
    assert p.rewrite(TEXT, "x") is None
    p, _ = make("openai", Resp(200, {"choices": [{"message": {"content": "x" * 500}}]}))
    assert p.rewrite("short", "x") is None
    p, _ = make("anthropic", key=None)
    assert p.rewrite(TEXT, "x") is None


def test_command_mode_with_and_without_selection():
    p, s = make("openai", Resp(200, {"choices": [{"message": {"content": "Short."}}]}))
    assert p.rewrite("make it shorter", "", selection="A long sentence here.",
                     mode="command") == "Short."
    assert "<selection>" in s.calls[-1][3]["messages"][1]["content"]
    p, _ = make("openai", Resp(200, {"choices": [{"message": {"content": "Dear team, ..."}}]}))
    assert p.rewrite("write a greeting", "", mode="command") == "Dear team, ..."
    p, _ = make("openai", Resp(200, {"choices": [{"message": {"content": "y" * 5000}}]}))
    assert p.rewrite("write an essay", "", mode="command") is None


def test_logs_never_contain_text_or_key(caplog):
    caplog.set_level(logging.DEBUG, logger="flowclone")
    p, _ = make("openai", Resp(200, {"choices": [{"message": {"content": "We meet at 3."}}]}))
    p.rewrite(TEXT, "x")
    p, _ = make("openai", Resp(500))
    p.rewrite(TEXT, "x")
    logged = " ".join(r.getMessage() for r in caplog.records)
    assert "meet" not in logged and SECRET not in logged and logged


def test_test_button_messages():
    p, _ = make("anthropic", key=None)
    assert p.test() == (False, "No API key saved for this provider")
    p, _ = make("anthropic", Resp(401))
    assert p.test() == (False, "the API key was rejected")
