"""Text injection into the focused app — the same trick Wispr Flow uses.

Primary mode 'paste': save clipboard -> put transcript on clipboard -> simulate
Ctrl+V -> restore previous clipboard. Works in ~95% of apps, instant for long text.
Fallback mode 'type': per-character Unicode typing (SendInput) for apps that block
paste (some terminals, RDP sessions) — configure them in injection.type_mode_apps.

Known quirk (Wispr Flow has it too): only plain-text clipboard content is
saved/restored; a copied image is lost when a dictation fires.
"""
import time

import keyboard
import pyperclip


def insert_text(text: str, mode: str = "paste", restore_delay_ms: int = 300,
                press_enter: bool = False):
    if not text:
        return
    if mode == "type":
        keyboard.write(text)
    else:
        old = None
        try:
            old = pyperclip.paste()
        except pyperclip.PyperclipException:
            pass  # non-text clipboard; nothing we can restore
        pyperclip.copy(text)
        time.sleep(0.06)          # let the clipboard settle before the paste lands
        keyboard.send("ctrl+v")
        time.sleep(max(restore_delay_ms, 50) / 1000)
        if old is not None:
            try:
                pyperclip.copy(old)
            except pyperclip.PyperclipException:
                pass
    if press_enter:
        time.sleep(0.08)
        keyboard.send("enter")


def copy_to_clipboard(text: str):
    try:
        pyperclip.copy(text)
    except pyperclip.PyperclipException as e:
        print(f"[inject] clipboard copy failed: {e}")
