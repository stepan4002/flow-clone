"""Settings: every control saves immediately. Hotkey and engine changes need a restart."""
import os
import threading

from PySide6.QtCore import QObject, Qt, Signal
from PySide6.QtWidgets import (QComboBox, QDialog, QHBoxLayout, QLineEdit, QSpinBox,
                               QStackedWidget, QVBoxLayout, QWidget)

from ... import autostart, credstore, paths
from ...llm import DEFAULT_MODELS, PROVIDERS, Polisher, is_cloud, key_slot
from ..widgets import (Card, Segmented, Toggle, button, keycaps, label, page, separator,
                       setting_row)


class _Sig(QObject):
    hotkey = Signal(str)
    test = Signal(bool, str)


def input_devices() -> list[tuple[int | None, str]]:
    """(index, name) of microphones on the default host API (no MME/WASAPI duplicates)."""
    out = [(None, "System default")]
    try:
        import sounddevice as sd
        default_api = sd.query_hostapis(sd.default.hostapi)["name"] if sd.default.hostapi >= 0 else None
        for i, d in enumerate(sd.query_devices()):
            if d["max_input_channels"] <= 0:
                continue
            if default_api and sd.query_hostapis(d["hostapi"])["name"] != default_api:
                continue
            out.append((i, d["name"]))
    except Exception:
        pass
    return out


class HotkeyDialog(QDialog):
    """Press the new push-to-talk combination; Esc cancels."""

    def __init__(self, app, parent):
        super().__init__(parent)
        self.app = app
        self.setWindowTitle("Change hotkey")
        self.setModal(True)
        self.setMinimumWidth(420)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(24, 22, 24, 22)
        lay.addWidget(label("Press and release the keys you want to hold for dictation", "h3",
                            wrap=True))
        lay.addWidget(label("Use at least one modifier (Ctrl, Alt, Shift or Win). "
                            "Esc cancels.", "muted", wrap=True))
        self.shown = label("Waiting for keys…", "h2")
        lay.addWidget(self.shown)
        self.result: list[str] | None = None
        self._closed = False
        self._sig = _Sig()
        self._sig.hotkey.connect(self._got)
        app.suspend_hotkeys()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            import keyboard
            combo = keyboard.read_hotkey(suppress=False)
        except Exception:
            combo = "esc"
        self._sig.hotkey.emit(combo)

    def _got(self, combo):
        if self._closed:  # the dialog was closed; this is a late result of its key reader
            return
        keys = [k.strip().lower().replace("left ", "").replace("right ", "")
                for k in combo.split("+") if k.strip()]
        keys = ["windows" if k in ("win", "left windows", "right windows") else k for k in keys]
        if not keys or keys == ["esc"] or "esc" in keys:
            self.reject()
            return
        mods = {"ctrl", "alt", "shift", "windows"}
        if not any(k in mods for k in keys):
            self.shown.setText("Needs a modifier — try again")
            threading.Thread(target=self._read, daemon=True).start()
            return
        order = ["ctrl", "alt", "shift", "windows"]
        self.result = sorted(dict.fromkeys(keys), key=lambda k: order.index(k) if k in order else 9)
        self.accept()

    def done(self, r):  # noqa: D401 - Qt override
        if not self._closed:
            self._closed = True
            self.app.resume_hotkeys()
        super().done(r)


class SettingsPage:
    name = "settings"

    def __init__(self, app):
        self.app = app
        t = app.theme
        c = app.config
        self._sig = _Sig()
        self._sig.test.connect(self._test_done)
        self.widget, col = page("Settings")

        # ---------- general ----------
        gen = Card("General")
        self.autostart = Toggle(t, autostart.is_autostart_enabled(), "Launch at sign-in")
        self.autostart.toggled.connect(self._set_autostart)
        gen.body.addWidget(setting_row("Launch FlowClone when you sign in", "", self.autostart))
        gen.body.addWidget(separator())
        self.theme_seg = Segmented([("system", "System"), ("dark", "Dark"), ("light", "Light")],
                                   c["ui"].get("theme", "system"), "Theme")
        self.theme_seg.changed.connect(lambda v: app.save_settings(
            lambda cc: cc["ui"].__setitem__("theme", v)))
        gen.body.addWidget(setting_row("Appearance", "", self.theme_seg))
        gen.body.addWidget(separator())
        gen.body.addWidget(self._toggle("Sounds", "A short tone when recording starts and stops.",
                                        ("sounds",)))
        gen.body.addWidget(self._toggle("Show the status pill",
                                        "Listening / transcribing indicator near the bottom of "
                                        "the screen.", ("overlay", "enabled")))
        col.addWidget(gen)

        # ---------- dictation ----------
        dic = Card("Dictation")
        hk = QWidget()
        hk_l = QHBoxLayout(hk)
        hk_l.setContentsMargins(0, 0, 0, 0)
        self.keys_box = QHBoxLayout()
        hk_l.addLayout(self.keys_box)
        change = button("Change", None, "keyboard", t)
        change.clicked.connect(self._change_hotkey)
        hk_l.addWidget(change)
        self._render_keys()
        dic.body.addWidget(setting_row("Push-to-talk hotkey", "Hold to talk, release to insert.", hk))
        dic.body.addWidget(separator())
        self.cmd = QComboBox()
        for key, text in (("shift", "Shift"), ("alt", "Alt"), ("", "Off")):
            self.cmd.addItem(text, key)
        self.cmd.setCurrentIndex(max(0, self.cmd.findData(c["hotkeys"].get("command_modifier",
                                                                             "shift"))))
        self.cmd.currentIndexChanged.connect(lambda _: app.save_settings(
            lambda cc: cc["hotkeys"].__setitem__("command_modifier", self.cmd.currentData())))
        dic.body.addWidget(setting_row("Command Mode key", "Hold it together with the hotkey to "
                                       "edit selected text by voice (needs AI).", self.cmd))
        dic.body.addWidget(separator())
        dic.body.addWidget(self._toggle("Double-tap for hands-free",
                                        "Double-tap the hotkey to keep recording without holding.",
                                        ("double_tap_hands_free",)))
        self.hf = QSpinBox()
        self.hf.setRange(1, 20)  # the whole recording is transcribed in one go
        self.hf.setSuffix(" min")
        self.hf.setValue(int(c.get("hands_free_max_minutes", 20)))
        self.hf.valueChanged.connect(lambda v: app.save_settings(
            lambda cc: cc.__setitem__("hands_free_max_minutes", v)))
        dic.body.addWidget(setting_row("Hands-free stops after", "", self.hf))
        dic.body.addWidget(separator())
        self.mic = QComboBox()
        self.mic.setMinimumWidth(260)
        self.mic.currentIndexChanged.connect(self._mic_changed)
        dic.body.addWidget(setting_row("Microphone", "", self.mic))
        col.addWidget(dic)

        # ---------- text ----------
        txt = Card("Text", "Rules applied to every dictation (see Styles for per-app writing).")
        for title, desc, key in (
                ("Remove filler words", "um, uh, er…", "remove_fillers"),
                ("Spoken punctuation", "\"comma\", \"period\", \"new line\"…", "spoken_punctuation"),
                ("\"Scratch that\"", "Deletes what you said before it.", "scratch_that"),
                ("Capitalize sentences", "", "capitalize"),
                ("No full stop in chat apps", "For casual-style apps.",
                 "drop_trailing_period_in_casual_apps"),
                ("\"Press enter\" sends", "Say it at the end to press Enter.", "press_enter_command")):
            txt.body.addWidget(self._toggle(title, desc, ("cleanup", key)))
        self.inject = Segmented([("paste", "Paste"), ("type", "Type")],
                                c["injection"].get("mode", "paste"), "Insertion method")
        self.inject.changed.connect(lambda v: app.save_settings(
            lambda cc: cc["injection"].__setitem__("mode", v)))
        txt.body.addWidget(separator())
        txt.body.addWidget(setting_row("How text is inserted", "Type is slower but works in apps "
                                       "that block pasting.", self.inject))
        col.addWidget(txt)

        # ---------- AI ----------
        ai = Card("AI rewrite (optional)")
        self.ai_on = Toggle(t, c["llm"].get("enabled", False), "AI rewrite")
        self.ai_on.toggled.connect(lambda on: (app.save_settings(
            lambda cc: cc["llm"].__setitem__("enabled", on)), self._ai_visible()))
        ai.body.addWidget(setting_row("Rewrite with AI", "Smarter cleanup, per-app styles and "
                                      "Command Mode. Off = rules only.", self.ai_on))
        self.ai_box = QWidget()
        ab = QVBoxLayout(self.ai_box)
        ab.setContentsMargins(0, 0, 0, 0)
        ab.setSpacing(8)
        current = c["llm"].get("provider", "ollama")
        self.provider = Segmented([("ollama", "Ollama"), ("openai", "OpenAI-compatible"),
                                   ("anthropic", "Anthropic")],
                                  current if current in PROVIDERS else "ollama", "AI provider")
        self.provider.changed.connect(self._provider_changed)
        ab.addWidget(self.provider, 0, Qt.AlignLeft)
        self.prov_stack = QStackedWidget()
        self.prov_stack.addWidget(self._ollama_panel())
        self.prov_stack.addWidget(self._openai_panel())
        self.prov_stack.addWidget(self._anthropic_panel())
        ab.addWidget(self.prov_stack)
        self.privacy = label("", "faint", wrap=True)
        ab.addWidget(self.privacy)
        tr = QHBoxLayout()
        self.test_btn = button("Test connection", None, "check", t)
        self.test_btn.clicked.connect(self._test)
        self.test_out = label("", "muted")
        tr.addWidget(self.test_btn)
        tr.addWidget(self.test_out, 1)
        ab.addLayout(tr)
        ab.addWidget(self._toggle("Use each app's style", "Off: AI always uses the Clean style.",
                                  ("llm", "tone_matching")))
        ai.body.addWidget(self.ai_box)
        col.addWidget(ai)

        # ---------- speech model ----------
        sm = Card("Speech model")
        frozen = paths.is_frozen()
        engine = "Parakeet TDT 0.6B v3 (25 languages, on this PC)" if frozen or \
            c.get("engine") == "parakeet" else f"Whisper {c.get('whisper_model', 'small')}"
        sm.body.addWidget(setting_row("Engine", "", label(engine, "muted")))
        self.model_status = label("Loading…", "chip")
        status_row = QWidget()
        sr = QHBoxLayout(status_row)
        sr.setContentsMargins(0, 0, 0, 0)
        sr.addWidget(self.model_status)
        self.retry = button("Retry download", None, "refresh", t)
        self.retry.clicked.connect(lambda: app.retry_model_load())
        sr.addWidget(self.retry)
        folder = button("Open folder", "ghost", "folder", t)
        folder.clicked.connect(lambda: os.startfile(str(paths.models_dir()))
                               if paths.models_dir().exists() else None)
        sr.addWidget(folder)
        sm.body.addWidget(setting_row("Status", "", status_row))
        col.addWidget(sm)

        self.restart_card = Card(accent=True)
        rr = QHBoxLayout()
        rr.addWidget(label("Restart FlowClone to use the new hotkey.", "h3"))
        rr.addStretch()
        rb = button("Restart now", "primary", "refresh", t)
        rb.clicked.connect(lambda: app.restart())
        rr.addWidget(rb)
        self.restart_card.body.addLayout(rr)
        self.restart_card.hide()
        col.insertWidget(1, self.restart_card)
        col.addStretch()

        app.bridge.model_status.connect(self._model_status)
        self._model_status(*app.model_status)
        self._provider_changed(self.provider.value(), save=False)
        self._ai_visible()

    # ---------- helpers ----------

    def _toggle(self, title, desc, path):
        c = self.app.config
        node = c
        for k in path[:-1]:
            node = node[k]
        tg = Toggle(self.app.theme, bool(node.get(path[-1], True)), title)

        def save(on):
            def mut(cc):
                n = cc
                for k in path[:-1]:
                    n = n[k]
                n[path[-1]] = on
            self.app.save_settings(mut)
        tg.toggled.connect(save)
        return setting_row(title, desc, tg)

    def _line(self, value, placeholder, on_save, password=False, name=""):
        e = QLineEdit(value)
        e.setPlaceholderText(placeholder)
        e.setMinimumWidth(320)
        e.setAccessibleName(name or placeholder)
        if password:
            e.setEchoMode(QLineEdit.Password)
        e.editingFinished.connect(lambda: on_save(e.text().strip()))
        return e

    def _llm_set(self, key):
        return lambda v: self.app.save_settings(lambda cc: cc["llm"].__setitem__(key, v))

    def _ollama_panel(self):
        w = QWidget()
        lay = QVBoxLayout(w)
        lay.setContentsMargins(0, 0, 0, 0)
        c = self.app.config["llm"]
        lay.addWidget(setting_row("Ollama address", "Install Ollama, then run: "
                                  "ollama pull qwen3:1.7b",
                                  self._line(c.get("url", ""), "http://localhost:11434",
                                             self._set_ollama_url, name="Ollama address")))
        lay.addWidget(setting_row("Model", "", self._line(c.get("model", ""), DEFAULT_MODELS["ollama"],
                                                          self._llm_set("model"), name="Ollama model")))
        return w

    def _key_field(self, provider):
        w = QWidget()
        row = QHBoxLayout(w)
        row.setContentsMargins(0, 0, 0, 0)
        e = self._line("", "", lambda v: self._save_key(provider, v), password=True,
                       name=f"{provider} API key")
        rm = button("Remove", "ghost", "trash", self.app.theme, tooltip="Remove the saved key")
        rm.clicked.connect(lambda: self._remove_key(provider))
        row.addWidget(e, 1)
        row.addWidget(rm)
        self._key_widgets = getattr(self, "_key_widgets", {})
        self._key_widgets[provider] = (e, rm)
        self._update_key_field(provider)
        return w

    def _slot(self, provider):
        return key_slot(provider, self.app.config["llm"])

    def _update_key_field(self, provider):
        e, rm = self._key_widgets[provider]
        has = credstore.has_key(self._slot(provider))
        e.clear()
        e.setPlaceholderText("Saved — paste a new key to replace" if has else "Paste your API key")
        rm.setVisible(has)

    def _save_key(self, provider, value):
        if not value:
            return  # an empty field means "keep the saved key"; Remove deletes it
        try:
            credstore.set_key(self._slot(provider), value)
            self.test_out.setText("Key saved in Windows Credential Manager.")
        except Exception as e:
            self.test_out.setText(f"Could not save the key ({type(e).__name__}).")
        self._update_key_field(provider)
        self.app.rebuild_polisher()

    def _remove_key(self, provider):
        credstore.delete_key(self._slot(provider))
        self.test_out.setText("Key removed.")
        self._update_key_field(provider)
        self.app.rebuild_polisher()

    def _set_api_base(self, v):
        self.app.save_settings(lambda cc: cc["llm"].__setitem__("api_base", v))
        self._update_key_field("openai")  # each address has its own saved key
        self._update_privacy()

    def _openai_panel(self):
        w = QWidget()
        lay = QVBoxLayout(w)
        lay.setContentsMargins(0, 0, 0, 0)
        c = self.app.config["llm"]
        lay.addWidget(setting_row("API address", "Leave empty for OpenAI. Works with Groq, "
                                  "OpenRouter, LM Studio and others.",
                                  self._line(c.get("api_base", ""), "https://api.openai.com/v1",
                                             self._set_api_base, name="API address")))
        lay.addWidget(setting_row("Model", "", self._line(c.get("api_model", ""),
                                                          DEFAULT_MODELS["openai"],
                                                          self._llm_set("api_model"),
                                                          name="Model")))
        lay.addWidget(setting_row("API key", "Not needed for a server on this PC.",
                                  self._key_field("openai")))
        return w

    def _anthropic_panel(self):
        w = QWidget()
        lay = QVBoxLayout(w)
        lay.setContentsMargins(0, 0, 0, 0)
        c = self.app.config["llm"]
        lay.addWidget(setting_row("Model", "", self._line(c.get("anthropic_model", ""),
                                                          DEFAULT_MODELS["anthropic"],
                                                          self._llm_set("anthropic_model"),
                                                          name="Anthropic model")))
        lay.addWidget(setting_row("API key", "", self._key_field("anthropic")))
        return w

    def _set_ollama_url(self, v):
        self.app.save_settings(lambda cc: cc["llm"].__setitem__("url", v))
        self._update_privacy()

    def _provider_changed(self, p, save=True):
        p = p if p in PROVIDERS else "ollama"
        self.prov_stack.setCurrentIndex(list(PROVIDERS).index(p))
        self.test_out.setText("")
        if save:
            self.app.save_settings(lambda cc: cc["llm"].__setitem__("provider", p))
        self._update_privacy()

    def _update_privacy(self):
        cfg = dict(self.app.config["llm"])
        cfg["provider"] = self.provider.value() or "ollama"
        if not is_cloud(cfg):
            self.privacy.setText("Runs on this computer — nothing leaves it.")
        elif cfg["provider"] == "ollama":
            self.privacy.setText("Privacy: this Ollama runs on another computer — the text of "
                                 "each dictation (and the selected text in Command Mode) is sent "
                                 "to it over your network, unencrypted with http://. Your audio "
                                 "is never sent.")
        else:
            self.privacy.setText("Privacy: with this provider the text of each dictation (and "
                                 "the selected text in Command Mode) is sent over the internet "
                                 "to the provider you chose, using your own key. Your audio is "
                                 "never sent. A server on this PC (LM Studio, llama.cpp) keeps "
                                 "everything local.")

    def _ai_visible(self):
        self.ai_box.setVisible(self.ai_on.isChecked())

    def _test(self):
        self.test_btn.setEnabled(False)
        self.test_out.setText("Testing…")
        cfg = dict(self.app.config["llm"])

        def work():
            try:
                ok, msg = Polisher(cfg).test()
            except Exception as e:  # never crash the UI on a test
                ok, msg = False, type(e).__name__
            self._sig.test.emit(ok, msg)
        threading.Thread(target=work, daemon=True).start()

    def _test_done(self, ok, msg):
        self.test_btn.setEnabled(True)
        self.test_out.setText(("✓ " if ok else "✗ ") + msg)

    def _set_autostart(self, on):
        try:
            autostart.set_autostart(on)
        except OSError as e:
            self.autostart.blockSignals(True)
            self.autostart.setChecked(not on)
            self.autostart.blockSignals(False)
            self.app.flash(f"Couldn't change \"launch at sign-in\" ({type(e).__name__})", 3000)

    def _render_keys(self):
        while self.keys_box.count():
            it = self.keys_box.takeAt(0)
            if it.widget():
                it.widget().deleteLater()
        self.keys_box.addWidget(keycaps(self.app.config["hotkeys"]["push_to_talk"]))

    def _change_hotkey(self):
        dlg = HotkeyDialog(self.app, self.widget)
        if dlg.exec() == QDialog.Accepted and dlg.result:
            keys = dlg.result
            self.app.save_settings(lambda cc: cc["hotkeys"].__setitem__("push_to_talk", keys))
            self._render_keys()
            self.restart_card.show()

    def _mic_changed(self, _i):
        if getattr(self, "_filling", False):
            return
        dev = self.mic.currentData()
        self.app.save_settings(lambda cc: cc["audio"].__setitem__("device", dev))

    def _model_status(self, state, pct):
        text = {"ready": "Ready", "loading": "Loading…", "downloading": f"Downloading {pct}%",
                "error": "Download failed"}.get(state, state)
        role = {"ready": "chip-ok", "error": "chip-err"}.get(state, "chip-warn")
        self.model_status.setText(text)
        self.model_status.setProperty("role", role)
        self.model_status.style().unpolish(self.model_status)
        self.model_status.style().polish(self.model_status)
        self.retry.setVisible(state == "error")

    def refresh(self):
        self._filling = True
        self.mic.clear()
        current = self.app.config["audio"].get("device")
        for idx, name in input_devices():
            self.mic.addItem(name, idx)
        i = self.mic.findData(current)
        self.mic.setCurrentIndex(i if i >= 0 else 0)
        self._filling = False
        if i < 0 and current is not None:
            # The saved microphone is gone (unplugged / renumbered): use the system default.
            self.app.save_settings(lambda cc: cc["audio"].__setitem__("device", None))
        self.autostart.blockSignals(True)
        self.autostart.setChecked(autostart.is_autostart_enabled())
        self.autostart.blockSignals(False)
