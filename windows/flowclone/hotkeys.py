"""Global hotkeys via the `keyboard` library (low-level Windows hook, no admin).

Push-to-talk is a hold-combo: press ALL configured keys -> on_press fires;
release ANY of them -> on_release fires. Esc while recording -> on_cancel.

Note: like every user-mode hook, this cannot see keys while an elevated (admin)
window is focused — run this app elevated too if you dictate into admin windows.
"""
import keyboard

_NORMALIZE = {
    "left ctrl": "ctrl", "right ctrl": "ctrl",
    "left shift": "shift", "right shift": "shift",
    "left alt": "alt", "alt gr": "alt",
    "left windows": "windows", "right windows": "windows",
}


def _norm(name: str) -> str:
    return _NORMALIZE.get(name, name)


class HotkeyManager:
    def __init__(self, config: dict, on_press, on_release, on_cancel,
                 on_paste_last, on_copy_last):
        hk = config["hotkeys"]
        self.combo = {k.lower() for k in hk["push_to_talk"]}
        self.on_press = on_press
        self.on_release = on_release
        self.on_cancel = on_cancel
        self._pressed: set[str] = set()
        self._armed = False

        keyboard.hook(self._handle, suppress=False)
        if hk.get("paste_last"):
            keyboard.add_hotkey(hk["paste_last"], on_paste_last)
        if hk.get("copy_last"):
            keyboard.add_hotkey(hk["copy_last"], on_copy_last)

    def _handle(self, event: keyboard.KeyboardEvent):
        name = _norm((event.name or "").lower())
        if event.event_type == keyboard.KEY_DOWN:
            if name == "esc":
                self.on_cancel()
            if name in self.combo:
                self._pressed.add(name)
                if not self._armed and self._pressed >= self.combo:
                    self._armed = True
                    self.on_press()
        else:  # KEY_UP
            if name in self.combo:
                if self._armed:
                    self._armed = False
                    # If the combo uses the Windows key and it's still held after
                    # another combo key was released, inject a harmless Ctrl tap so
                    # the shell doesn't treat the eventual bare Win-up as "open
                    # Start menu".
                    if "windows" in self.combo and name != "windows":
                        keyboard.send("ctrl")
                    self.on_release()
                self._pressed.discard(name)

    def stop(self):
        keyboard.unhook_all()
