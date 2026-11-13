"""Speech-to-text engines. Both run 100% locally.

- ParakeetEngine (default): NVIDIA Parakeet-TDT-0.6B-v3, int8 ONNX via onnx-asr.
  More accurate than Whisper large-v3 for English + 25 European languages
  (incl. Czech, French, Spanish — auto-detected) and 50-100x real-time on CPU.
  Model ~670 MB, downloaded once into %LOCALAPPDATA%\\FlowClone\\models by model_store
  (pinned + SHA-256 verified, resumable; reused from the Hugging Face cache if present).
- WhisperEngine: faster-whisper (CTranslate2). 90+ languages, auto-detect. Source
  installs only: it is not bundled in FlowClone.exe.
"""
import logging

from . import model_store, paths

log = logging.getLogger("flowclone")


class EngineUnavailable(Exception):
    """The configured engine isn't installed (e.g. Whisper in FlowClone.exe)."""


class ParakeetEngine:
    name = "parakeet-tdt-0.6b-v3"

    def __init__(self, progress_cb=None, cancel_event=None):
        model_dir = model_store.ensure(progress_cb, cancel_event)
        import onnx_asr  # deferred: heavy import
        log.info("loading Parakeet-TDT-0.6B-v3 from %s", model_dir)
        # Only ever pass a complete, verified folder: with an existing `path` onnx_asr works
        # offline and a partial folder would fail; a missing one would make it download
        # into the folder on its own, unverified.
        self.model = onnx_asr.load_model("nemo-parakeet-tdt-0.6b-v3", path=str(model_dir),
                                         quantization="int8")
        log.info("Parakeet ready")

    def transcribe(self, audio, sample_rate: int, hint_words=None, language=None) -> str:
        import numpy as np
        result = self.model.recognize(audio.astype(np.float32), sample_rate=sample_rate)
        return (result or "").strip()


class WhisperEngine:
    def __init__(self, model_size: str = "small"):
        try:
            from faster_whisper import WhisperModel  # deferred: heavy import
        except ImportError as e:
            if paths.is_frozen():
                raise EngineUnavailable("The Whisper engine is not included in FlowClone.exe") from e
            raise EngineUnavailable("faster-whisper is not installed "
                                    "(pip install faster-whisper)") from e
        self.name = f"whisper-{model_size}"
        log.info("loading faster-whisper '%s' (downloads on first run)", model_size)
        self.model = WhisperModel(model_size, device="auto", compute_type="int8")
        log.info("Whisper ready")

    def transcribe(self, audio, sample_rate: int, hint_words=None, language=None) -> str:
        import numpy as np
        # Dictionary words are passed as an initial prompt to bias recognition.
        prompt = ("Glossary: " + ", ".join(hint_words)) if hint_words else None
        segments, _info = self.model.transcribe(
            audio.astype(np.float32),
            language=language,
            initial_prompt=prompt,
            vad_filter=True,
        )
        return " ".join(s.text.strip() for s in segments).strip()


def create_engine(config: dict, progress_cb=None, cancel_event=None, on_fallback=None):
    """The configured engine. Only a *missing* Whisper engine falls back to Parakeet; real
    Whisper errors (bad model, CUDA, ...) are raised, never silently swapped."""
    if config.get("engine") == "whisper":
        try:
            return WhisperEngine(config.get("whisper_model", "small"))
        except EngineUnavailable as e:
            log.warning("%s — using Parakeet instead", e)
            if on_fallback:
                on_fallback(str(e))
    return ParakeetEngine(progress_cb, cancel_event)
