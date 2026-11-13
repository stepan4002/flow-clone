"""Speech-to-text engines. Both run 100% locally and auto-download their model on first use.

- ParakeetEngine (default): NVIDIA Parakeet-TDT-0.6B-v3, int8 ONNX via onnx-asr.
  More accurate than Whisper large-v3 for English + 25 European languages
  (incl. Czech, French, Spanish — auto-detected) and 50-100x real-time on CPU.
  Model ~330 MB, cached under ~/.cache/huggingface.
- WhisperEngine: faster-whisper (CTranslate2). 90+ languages, auto-detect.
"""
import numpy as np


class ParakeetEngine:
    name = "parakeet-tdt-0.6b-v3"

    def __init__(self):
        import onnx_asr  # deferred: heavy import
        print("[stt] loading Parakeet-TDT-0.6B-v3 (downloads ~330 MB on first run)...")
        self.model = onnx_asr.load_model("nemo-parakeet-tdt-0.6b-v3", quantization="int8")
        print("[stt] Parakeet ready")

    def transcribe(self, audio: np.ndarray, sample_rate: int, hint_words=None, language=None) -> str:
        result = self.model.recognize(audio.astype(np.float32), sample_rate=sample_rate)
        return (result or "").strip()


class WhisperEngine:
    def __init__(self, model_size: str = "small"):
        from faster_whisper import WhisperModel  # deferred: heavy import
        self.name = f"whisper-{model_size}"
        print(f"[stt] loading faster-whisper '{model_size}' (downloads on first run)...")
        self.model = WhisperModel(model_size, device="auto", compute_type="int8")
        print("[stt] Whisper ready")

    def transcribe(self, audio: np.ndarray, sample_rate: int, hint_words=None, language=None) -> str:
        # Dictionary words are passed as an initial prompt to bias recognition.
        prompt = ("Glossary: " + ", ".join(hint_words)) if hint_words else None
        segments, _info = self.model.transcribe(
            audio.astype(np.float32),
            language=language,
            initial_prompt=prompt,
            vad_filter=True,
        )
        return " ".join(s.text.strip() for s in segments).strip()


def create_engine(config: dict):
    if config.get("engine") == "whisper":
        return WhisperEngine(config.get("whisper_model", "small"))
    return ParakeetEngine()
