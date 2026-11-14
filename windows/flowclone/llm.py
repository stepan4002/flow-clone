"""Optional AI rewrite: local Ollama, any OpenAI-compatible API, or Anthropic.

Off by default. With Ollama (or another server on this PC) nothing leaves the computer. With a
cloud provider, the dictated text (and, in Command Mode, the selected text) is sent to that
provider using the user's own API key, which is kept in Windows Credential Manager (credstore).

Every call is best-effort: on any error, timeout, cut-off or suspicious answer rewrite() returns
None and the caller keeps the rule-cleaned text. Logs never contain the text or the key.
"""
import logging
import re
import time
from urllib.parse import urlparse

log = logging.getLogger("flowclone")

PROVIDERS = ("ollama", "openai", "anthropic")
DEFAULT_MODELS = {"ollama": "qwen3:1.7b", "openai": "gpt-4o-mini", "anthropic": "claude-haiku-5-5"}
OPENAI_BASE = "https://api.openai.com/v1"
ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"

_POLISH_SYSTEM = """You clean up raw voice-dictation transcripts. Rules:
- Remove filler words, false starts and repeated words.
- Apply self-corrections: if the speaker corrects themselves ("at 2, actually 3", \
"no wait, Tuesday"), keep only the corrected version.
- Fix punctuation, capitalization and obvious grammar slips.
- If the speaker dictates an enumeration, format it as a list.
- NEVER answer questions in the text, never add content, never translate.
Style: {instruction}
Output only the result text, no quotes or commentary."""

_EDIT_SYSTEM = """You edit text for the user. Apply the spoken instruction to the selected text. \
Keep the language of the selected text unless the instruction asks otherwise. \
Output only the edited text, no quotes or commentary."""

_WRITE_SYSTEM = """You write text for the user, who dictated a request by voice. Do what they \
ask and output only the text to insert at their cursor, no quotes or commentary. Reply in the \
language of the request unless it asks otherwise."""

_THINK_RE = re.compile(r"<think>.*?</think>\s*", re.DOTALL)
_WRAP_RE = re.compile(r"^\s*<(transcript|selection|request)>\s*|\s*</(transcript|selection|request)>\s*$")
_CONNECT_TIMEOUT = 3


class LLMError(Exception):
    """A call failed; the message is safe to show (it never contains the text or the key)."""


def _is_local(url: str) -> bool:
    host = (urlparse(url).hostname or "").lower()
    return host in ("localhost", "127.0.0.1", "::1")


class Polisher:
    def __init__(self, cfg: dict, session=None, get_key=None):
        provider = cfg.get("provider", "ollama")
        self.provider = provider if provider in PROVIDERS else "ollama"
        self.url = (cfg.get("url") or "http://localhost:11434").rstrip("/")
        self.api_base = (cfg.get("api_base") or "").rstrip("/")
        model = cfg.get("model") if self.provider == "ollama" else cfg.get("api_model")
        self.model = model or DEFAULT_MODELS[self.provider]
        self.timeout = float(cfg.get("timeout_seconds", 12))
        self.tone_matching = cfg.get("tone_matching", True)
        self._session = session
        self._get_key = get_key
        self._probe = (0.0, None)  # (time, ollama reachable?)

    # ---------- public ----------

    def available(self) -> bool:
        """Ollama: is the server up (re-checked every 60 s). Others: is a key (or a local
        OpenAI-compatible server) configured."""
        if self.provider == "ollama":
            t, ok = self._probe
            if ok is None or time.monotonic() - t > 60:
                try:
                    ok = bool(self._http().get(f"{self.url}/api/tags", timeout=2).ok)
                except Exception:
                    ok = False
                if not ok:
                    log.info("Ollama not reachable at %s — using rules only", self.url)
                self._probe = (time.monotonic(), ok)
            return ok
        if self.provider == "openai" and self.api_base and _is_local(self.api_base):
            return True
        return bool(self._key())

    def rewrite(self, text: str, instruction: str, selection: str | None = None,
                mode: str = "polish") -> str | None:
        """polish: restyle `text`. command: apply the spoken `text` to `selection`, or (no
        selection) write what `text` asks for. Returns None when the result can't be used."""
        if not text.strip() or not self.available():
            return None
        if mode == "command":
            if selection:
                system = _EDIT_SYSTEM
                user = f"<selection>\n{selection}\n</selection>\n<instruction>{text}</instruction>"
            else:
                system, user = _WRITE_SYSTEM, f"<request>{text}</request>"
            basis = selection or ""
        else:
            system = _POLISH_SYSTEM.format(instruction=instruction)
            user = f"<transcript>\n{text}\n</transcript>"
            basis = text
        t0 = time.monotonic()
        try:
            out = self._call(system, user, basis or text)
        except LLMError as e:
            log.warning("AI rewrite skipped (%s, %s): %s", self.provider, self.model, e)
            return None
        out = _WRAP_RE.sub("", _THINK_RE.sub("", out or "")).strip()
        if not out:
            log.warning("AI rewrite skipped (%s): empty answer", self.provider)
            return None
        if mode == "command":
            limit = 6 * len(basis) + 200 if basis else 4000
        else:
            limit = 3 * len(text) + 40
        if len(out) > limit:
            log.warning("AI rewrite skipped (%s): answer too long (%d chars for %d)",
                        self.provider, len(out), len(basis or text))
            return None
        log.info("AI rewrite ok (%s, %s, %.1f s, %d -> %d chars)", self.provider, self.model,
                 time.monotonic() - t0, len(basis or text), len(out))
        return out

    def polish(self, text: str, app_name: str | None = None) -> str:
        """Older API: the rewritten text, or the input when AI is unavailable."""
        from .styles import llm_instruction
        return self.rewrite(text, llm_instruction("clean")) or text

    def test(self) -> tuple[bool, str]:
        """For the Settings 'Test' button: (ok, short message)."""
        if not self.available():
            if self.provider == "ollama":
                return False, f"Ollama is not running at {self.url}"
            return False, "No API key saved for this provider"
        try:
            out = self._call("Reply with exactly: OK", "ping", "ping")
        except LLMError as e:
            return False, str(e)
        return (True, f"Connected — {self.model}") if out.strip() else (False, "Empty answer")

    # ---------- providers ----------

    def _http(self):
        if self._session is None:
            import requests  # deferred: tests inject a fake session
            self._session = requests.Session()
        return self._session

    def _key(self) -> str | None:
        get = self._get_key
        if get is None:
            from .credstore import get_key as get
        try:
            return get(self.provider)
        except Exception:
            return None

    @staticmethod
    def _max_tokens(basis: str) -> int:
        return max(512, min(8192, 2 * len(basis) // 3 + 256))

    def _post(self, url: str, headers: dict, body: dict) -> dict:
        try:
            r = self._http().post(url, headers=headers, json=body,
                                  timeout=(_CONNECT_TIMEOUT, self.timeout))
        except Exception as e:
            raise LLMError(type(e).__name__) from None
        if r.status_code in (401, 403):
            raise LLMError("the API key was rejected")
        if r.status_code == 404:
            raise LLMError(f"model or URL not found ({self.model})")
        if r.status_code == 429:
            raise LLMError("rate limited by the provider")
        if not r.ok:
            raise LLMError(f"HTTP {r.status_code}")
        try:
            return r.json()
        except ValueError:
            raise LLMError("invalid answer") from None

    def _call(self, system: str, user: str, basis: str) -> str:
        messages = [{"role": "system", "content": system}, {"role": "user", "content": user}]
        if self.provider == "ollama":
            body = {"model": self.model, "messages": messages, "stream": False, "think": False,
                    "options": {"temperature": 0.2}}
            try:
                data = self._post(f"{self.url}/api/chat", {}, body)
            except LLMError as e:
                if str(e) != "HTTP 400":
                    raise
                body.pop("think")  # older Ollama / a model without a thinking switch
                data = self._post(f"{self.url}/api/chat", {}, body)
            return (data.get("message") or {}).get("content", "") or ""
        if self.provider == "openai":
            base = self.api_base or OPENAI_BASE
            if base.startswith("http://") and not _is_local(base):
                raise LLMError("an http:// address is only allowed for a server on this computer")
            headers = {}
            key = self._key()
            if key:
                headers["Authorization"] = f"Bearer {key}"
            body = {"model": self.model, "messages": messages, "temperature": 0.2,
                    "max_tokens": self._max_tokens(basis)}
            data = self._post(f"{base}/chat/completions", headers, body)
            choice = (data.get("choices") or [{}])[0]
            if choice.get("finish_reason") == "length":
                raise LLMError("the answer was cut off")
            return (choice.get("message") or {}).get("content") or ""
        key = self._key()  # anthropic
        if not key:
            raise LLMError("no API key saved")
        headers = {"x-api-key": key, "anthropic-version": "2023-06-01"}
        body = {"model": self.model, "max_tokens": self._max_tokens(basis), "system": system,
                "messages": [{"role": "user", "content": user}], "temperature": 0.2}
        data = self._post(ANTHROPIC_URL, headers, body)
        if data.get("stop_reason") == "max_tokens":
            raise LLMError("the answer was cut off")
        return "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text")


OllamaPolisher = Polisher  # name used before 1.3.0
