"""Short audio cues (start / stop / error), like Wispr Flow's ping. winsound is
built into Python on Windows; beeps run in a worker thread because Beep blocks."""
import threading
import winsound

_TONES = {
    "start": [(880, 70), (1100, 60)],
    "stop": [(660, 70)],
    "error": [(220, 160)],
}


class Sounds:
    def __init__(self, enabled: bool = True):
        self.enabled = enabled

    def play(self, name: str):
        if not self.enabled or name not in _TONES:
            return
        threading.Thread(target=self._beep, args=(name,), daemon=True).start()

    @staticmethod
    def _beep(name: str):
        try:
            for freq, dur in _TONES[name]:
                winsound.Beep(freq, dur)
        except RuntimeError:
            pass
