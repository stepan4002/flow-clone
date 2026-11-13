"""Microphone capture via sounddevice: 16 kHz mono float32."""
import threading

import numpy as np
import sounddevice as sd


class Recorder:
    def __init__(self, sample_rate: int = 16000, device=None):
        self.sample_rate = sample_rate
        self.device = device
        self._chunks: list[np.ndarray] = []
        self._stream: sd.InputStream | None = None
        self._lock = threading.Lock()
        self.recording = False

    def start(self):
        with self._lock:
            if self.recording:
                return
            self._chunks = []
            self._stream = sd.InputStream(
                samplerate=self.sample_rate,
                channels=1,
                dtype="float32",
                device=self.device,
                callback=self._callback,
            )
            self._stream.start()
            self.recording = True

    def _callback(self, indata, frames, time_info, status):
        if status:
            print(f"[audio] {status}")
        self._chunks.append(indata[:, 0].copy())

    def stop(self) -> np.ndarray:
        with self._lock:
            if not self.recording:
                return np.zeros(0, dtype=np.float32)
            self.recording = False
            self._stream.stop()
            self._stream.close()
            self._stream = None
            if not self._chunks:
                return np.zeros(0, dtype=np.float32)
            return np.concatenate(self._chunks)

    def duration(self) -> float:
        return sum(len(c) for c in self._chunks) / self.sample_rate
