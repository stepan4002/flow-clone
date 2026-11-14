"""Global hotkeys via the `keyboard` library (low-level Windows hook, no admin).

Push-to-talk is a hold-combo: press ALL configured keys -> on_press(command) fires; release ANY
of them -> on_release() fires. Esc -> on_cancel. Holding the command modifier (default Shift)
together with the combo, in any order, starts Command Mode: on_press(command=True), or
on_command() when the modifier is added while already holding the combo.

Callbacks run on the hook thread and must return quickly (no sleeps, no UI work).

Note: like every user-mode hook, this cannot see keys while an elevated (admin) window is
focused — run FlowClone elevated too if you dictate into admin windows.
"""
import keyboard

_NORMALIZE = {
    "left ctrl": "ctrl", "right ctrl": "ctrl",
    "left shift": "shift", "right shift": "shift",
    "left alt": "alt", "alt gr": "alt", "right alt": "alt",
    "left windows": "windows", "right windows": "windows",
}


def _norm(name: str) -> str:
    return _NORMALIZE.get(name, name)


class HotkeyManager:
    def __init__(self, config: dict, on_press, on_release, on_cancel,
                 on_paste_last, on_copy_last, on_command=None):
        hk = config["hotkeys"]
        self.combo = {_norm(k.lower()) for k in hk["push_to_talk"]}
        self.command_key = None
        self.set_command_key(hk.get("command_modifier"))
        self.on_press = on_press
        self.on_release = on_release
        self.on_cancel = on_cancel
        self.on_command = on_command
        self._pressed: set[str] = set()
        self._mod_down = False
        self._armed = False
        self._suspended = False

        keyboard.hook(self._handle, suppress=False)
        if hk.get("paste_last"):
            keyboard.add_hotkey(hk["paste_last"], on_paste_last)
        if hk.get("copy_last"):
            keyboard.add_hotkey(hk["copy_last"], on_copy_last)

    def set_command_key(self, key: str | None):
        key = _norm((key or "").lower())
        self.command_key = key if key and key not in self.combo else None

    def suspend(self):
        """Ignore the hotkeys (e.g. while the user records a new combination)."""
        self._suspended = True
        self._armed = False
        self._pressed.clear()

    def resume(self):
        self._pressed.clear()
        self._mod_down = False
        self._suspended = False

    def keys_down(self) -> bool:
        """Is any key of the combo (or the command modifier) still physically held?"""
        keys = set(self.combo) | ({self.command_key} if self.command_key else set())
        try:
            return any(keyboard.is_pressed(k) for k in keys)
        except Exception:
            return False

    def _handle(self, event: keyboard.KeyboardEvent):
        if self._suspended:
            return
        name = _norm((event.name or "").lower())
        if event.event_type == keyboard.KEY_DOWN:
            if name == "esc":
                self.on_cancel()
            if self.command_key and name == self.command_key:
                if not self._mod_down and self._armed and self.on_command:
                    self.on_command()
                self._mod_down = True
            if name in self.combo:
                self._pressed.add(name)
                if not self._armed and self._pressed >= self.combo:
                    self._armed = True
                    self.on_press(self._mod_down)
        else:  # KEY_UP
            if self.command_key and name == self.command_key:
                self._mod_down = False
            if name in self.combo:
                if self._armed:
                    self._armed = False
                    # If the combo uses the Windows key and it's still held after another
                    # combo key was released, inject a harmless Ctrl tap so the shell doesn't
                    # treat the eventual bare Win-up as "open Start menu".
                    if "windows" in self.combo and name != "windows":
                        keyboard.send("ctrl")
                    self.on_release()
                self._pressed.discard(name)

    def stop(self):
        try:
            keyboard.unhook_all()
        except Exception:
            pass
