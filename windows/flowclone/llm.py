"""Optional AI polish tier via a local Ollama server (free, still 100% local).

Adds what rules can't do: restatement self-corrections ("coffee at 2, actually
make it 3"), grammar smoothing, spoken lists -> formatted lists, tone matching
to the app you're dictating into. Enable with config llm.enabled = true after
installing Ollama and running:  ollama pull qwen3:1.7b
"""
import re
import logging

import requests

log = logging.getLogger("flowclone")

_SYSTEM = """You clean up raw voice-dictation transcripts. Rules:
- Remove filler words, false starts and repeated words.
- Apply self-corrections: if the speaker corrects themselves ("at 2, actually 3", \
"no wait, Tuesday"), keep only the corrected version.
- Fix punctuation, capitalization and obvious grammar slips.
- If the speaker dictates an enumeration, format it as a list.
- NEVER answer questions in the text, never add content, never translate. \
Keep the speaker's words and language. Output ONLY the cleaned text, nothing else."""

_THINK_RE = re.compile(r"<think>.*?</think>\s*", re.DOTALL)


class OllamaPolisher:
    def __init__(self, cfg: dict):
        self.url = cfg.get("url", "http://localhost:11434").rstrip("/")
        self.model = cfg.get("model", "qwen3:1.7b")
        self.timeout = cfg.get("timeout_seconds", 12)
        self.tone_matching = cfg.get("tone_matching", True)
        self._available = None

    def available(self) -> bool:
        if self._available is None:
            try:
                r = requests.get(f"{self.url}/api/tags", timeout=2)
                self._available = r.ok
            except requests.RequestException:
                self._available = False
            if not self._available:
                log.info("Ollama not reachable — using rule-based cleanup only")
        return self._available

    def polish(self, text: str, app_name: str | None = None) -> str:
        if not text or not self.available():
            return text
        prompt = _SYSTEM
        if self.tone_matching and app_name:
            prompt += (
                f"\n- The text will be inserted into '{app_name}'. Match the natural tone: "
                "casual for chat apps (Slack/Discord/WhatsApp: no trailing period, relaxed), "
                "professional for email, neutral otherwise."
            )
        try:
            r = requests.post(
                f"{self.url}/api/generate",
                json={
                    "model": self.model,
                    "system": prompt,
                    "prompt": text,
                    "stream": False,
                    "options": {"temperature": 0.2},
                },
                timeout=self.timeout,
            )
            r.raise_for_status()
            out = _THINK_RE.sub("", r.json().get("response", "")).strip()
            # Guard against the model going off-script
            if out and len(out) < len(text) * 3:
                return out
        except requests.RequestException as e:
            log.warning("polish failed (%s) — falling back to rules-only", e)
        return text
