"""FlowClone Hub — the main window (like Wispr Flow's 'Flow Hub').

Tabs: Home (stats + recent dictations), Dictionary, Snippets, Settings, About.
Must be created on the tkinter thread: tray calls overlay.schedule(lambda: open_hub(app)).
"""
import json
import logging
import tkinter as tk
from tkinter import messagebox, ttk

from . import config as cfg, paths
from .config import CONFIG_PATH, DICTIONARY_PATH, HISTORY_PATH, SNIPPETS_PATH

_AUTOSTART_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_AUTOSTART_NAME = "FlowClone"

_hub = None


def open_hub(app):
    global _hub
    if _hub is not None and _hub.win.winfo_exists():
        _hub.win.deiconify()
        _hub.win.lift()
        _hub.win.focus_force()
        return
    _hub = HubWindow(app)


# ---------- autostart (HKCU Run key) ----------

log = logging.getLogger("flowclone")


def _autostart_value() -> str | None:
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _AUTOSTART_KEY) as k:
            value, _type = winreg.QueryValueEx(k, _AUTOSTART_NAME)
        return str(value)
    except OSError:
        return None


def is_autostart_enabled() -> bool:
    """True only if the Run value starts THIS FlowClone (not another install)."""
    value = _autostart_value()
    return value is not None and value.strip().lower() == paths.launch_command().lower()


def _command_target(command: str) -> str:
    """The executable path of a Run-key command line ("C:\\x\\y.exe" "arg" -> C:\\x\\y.exe)."""
    command = command.strip()
    if command.startswith('"'):
        return command[1:].split('"', 1)[0]
    return command.split(" ", 1)[0]


def ensure_autostart_current() -> None:
    """Repair a Run value left pointing at a FlowClone that no longer exists (e.g. after
    moving from the script install to FlowClone.exe). Another existing install is left alone."""
    import os
    value = _autostart_value()
    if value is None or value.strip().lower() == paths.launch_command().lower():
        return
    if os.path.exists(_command_target(value)):
        log.info("autostart points to another FlowClone install (%s); leaving it", value)
        return
    try:
        set_autostart(True)
        log.info("autostart repaired: %s -> %s", value, paths.launch_command())
    except OSError as e:
        log.warning("could not repair autostart: %s", e)


def set_autostart(enabled: bool):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _AUTOSTART_KEY, 0,
                        winreg.KEY_SET_VALUE) as k:
        if enabled:
            winreg.SetValueEx(k, _AUTOSTART_NAME, 0, winreg.REG_SZ, paths.launch_command())
        else:
            try:
                winreg.DeleteValue(k, _AUTOSTART_NAME)
            except OSError:
                pass


class HubWindow:
    def __init__(self, app):
        self.app = app
        self.win = tk.Toplevel(app.overlay.root)
        self.win.title("FlowClone Hub")
        self.win.geometry("780x560")
        self.win.minsize(640, 480)

        nb = ttk.Notebook(self.win)
        nb.pack(fill="both", expand=True, padx=8, pady=8)
        self.nb = nb
        nb.add(self._home_tab(nb), text="  Home  ")
        nb.add(self._dictionary_tab(nb), text="  Dictionary  ")
        nb.add(self._snippets_tab(nb), text="  Snippets  ")
        nb.add(self._settings_tab(nb), text="  Settings  ")
        nb.add(self._about_tab(nb), text="  About  ")

    # ---------------- Home ----------------

    def _home_tab(self, nb):
        f = ttk.Frame(nb, padding=12)
        s = self.app.stats
        stats_row = ttk.Frame(f)
        stats_row.pack(fill="x", pady=(0, 10))
        for title, value in [
            ("Today", f"{s.today()} words"),
            ("Last 7 days", f"{s.this_week()} words"),
            ("All time", f"{s.data['total_words']} words"),
            ("Dictations", str(s.data["total_dictations"])),
        ]:
            card = ttk.LabelFrame(stats_row, text=title, padding=10)
            card.pack(side="left", expand=True, fill="x", padx=4)
            ttk.Label(card, text=value, font=("Segoe UI", 13, "bold")).pack()

        ttk.Label(f, text="Recent dictations (double-click to copy):").pack(anchor="w")
        cols = ("time", "app", "text")
        tree = ttk.Treeview(f, columns=cols, show="headings", height=14)
        tree.heading("time", text="Time"); tree.column("time", width=130, stretch=False)
        tree.heading("app", text="App"); tree.column("app", width=110, stretch=False)
        tree.heading("text", text="Text"); tree.column("text", width=440)
        tree.pack(fill="both", expand=True, pady=4)
        self._history_tree = tree
        self._load_history()
        tree.bind("<Double-1>", self._copy_selected_history)

        row = ttk.Frame(f); row.pack(fill="x", pady=4)
        ttk.Button(row, text="Copy selected", command=self._copy_selected_history).pack(side="left")
        ttk.Button(row, text="Refresh", command=self._load_history).pack(side="left", padx=6)

        return f

    def _load_history(self):
        tree = self._history_tree
        tree.delete(*tree.get_children())
        try:
            lines = HISTORY_PATH.read_text(encoding="utf-8").splitlines()[-50:]
        except OSError:
            lines = []
        for line in reversed(lines):
            try:
                e = json.loads(line)
                tree.insert("", "end", values=(e.get("ts", ""), e.get("app", ""),
                                               e.get("text", "").replace("\n", " ⏎ ")))
            except json.JSONDecodeError:
                continue

    def _copy_selected_history(self, _event=None):
        sel = self._history_tree.selection()
        if sel:
            text = self._history_tree.item(sel[0], "values")[2].replace(" ⏎ ", "\n")
            self.win.clipboard_clear()
            self.win.clipboard_append(text)

    # ---------------- Dictionary ----------------

    def _dictionary_tab(self, nb):
        f = ttk.Frame(nb, padding=12)
        lex = self.app.lexicon

        words_frame = ttk.LabelFrame(f, text="Words (protect casing / bias recognition)", padding=8)
        words_frame.pack(fill="x", pady=4)
        self._words_list = tk.Listbox(words_frame, height=4)
        self._words_list.pack(fill="x")
        for w in lex.words:
            self._words_list.insert("end", w)
        row = ttk.Frame(words_frame); row.pack(fill="x", pady=4)
        self._word_entry = ttk.Entry(row)
        self._word_entry.pack(side="left", expand=True, fill="x")
        ttk.Button(row, text="Add", command=self._add_word).pack(side="left", padx=4)
        ttk.Button(row, text="Remove selected", command=self._remove_word).pack(side="left")

        corr = ttk.LabelFrame(f, text='Corrections — always fix a misheard word ("Eric" → "Erik")', padding=8)
        corr.pack(fill="both", expand=True, pady=4)
        self._corr_tree = self._pair_editor(corr, lex.corrections, "Heard", "Should be",
                                            self._save_dictionary)

        repl = ttk.LabelFrame(f, text='Replacements — shorthand you say → text inserted ("c30" → link)', padding=8)
        repl.pack(fill="both", expand=True, pady=4)
        self._repl_tree = self._pair_editor(repl, lex.replacements, "You say", "Inserted text",
                                            self._save_dictionary)
        return f

    def _pair_editor(self, parent, data: dict, col1: str, col2: str, on_change):
        tree = ttk.Treeview(parent, columns=("a", "b"), show="headings", height=4)
        tree.heading("a", text=col1); tree.column("a", width=160, stretch=False)
        tree.heading("b", text=col2)
        tree.pack(fill="both", expand=True)
        for k, v in data.items():
            tree.insert("", "end", values=(k, v.replace("\n", "\\n")))
        row = ttk.Frame(parent); row.pack(fill="x", pady=4)
        e1 = ttk.Entry(row, width=22); e1.pack(side="left")
        e2 = ttk.Entry(row); e2.pack(side="left", expand=True, fill="x", padx=4)

        def add():
            if e1.get().strip() and e2.get().strip():
                tree.insert("", "end", values=(e1.get().strip(), e2.get().strip()))
                e1.delete(0, "end"); e2.delete(0, "end")
                on_change()

        def remove():
            for item in tree.selection():
                tree.delete(item)
            on_change()

        ttk.Button(row, text="Add", command=add).pack(side="left")
        ttk.Button(row, text="Remove", command=remove).pack(side="left", padx=4)
        return tree

    def _add_word(self):
        w = self._word_entry.get().strip()
        if w:
            self._words_list.insert("end", w)
            self._word_entry.delete(0, "end")
            self._save_dictionary()

    def _remove_word(self):
        for i in reversed(self._words_list.curselection()):
            self._words_list.delete(i)
        self._save_dictionary()

    @staticmethod
    def _tree_to_dict(tree) -> dict:
        out = {}
        for item in tree.get_children():
            a, b = tree.item(item, "values")
            out[a] = b.replace("\\n", "\n")
        return out

    def _save_dictionary(self):
        data = {
            "words": list(self._words_list.get(0, "end")),
            "corrections": self._tree_to_dict(self._corr_tree),
            "replacements": self._tree_to_dict(self._repl_tree),
        }
        DICTIONARY_PATH.write_text(json.dumps(data, indent=2, ensure_ascii=False),
                                   encoding="utf-8")
        self.app.lexicon.reload()

    # ---------------- Snippets ----------------

    def _snippets_tab(self, nb):
        f = ttk.Frame(nb, padding=12)
        ttk.Label(f, text="Say the trigger phrase during a dictation and the expansion "
                          "is inserted in its place.").pack(anchor="w", pady=(0, 6))
        frame = ttk.LabelFrame(f, text="Snippets", padding=8)
        frame.pack(fill="both", expand=True)
        self._snip_tree = self._pair_editor(frame, self.app.lexicon.snippets,
                                            "Trigger phrase", "Expansion",
                                            self._save_snippets)
        return f

    def _save_snippets(self):
        SNIPPETS_PATH.write_text(
            json.dumps(self._tree_to_dict(self._snip_tree), indent=2, ensure_ascii=False),
            encoding="utf-8")
        self.app.lexicon.reload()

    # ---------------- Settings ----------------

    def _settings_tab(self, nb):
        outer = ttk.Frame(nb)
        canvas = tk.Canvas(outer, highlightthickness=0)
        scroll = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        f = ttk.Frame(canvas, padding=12)
        f.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=f, anchor="nw")
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        c = self.app.config
        self._vars = {}

        def check(parent, label, *path):
            v = tk.BooleanVar(value=self._get(c, path))
            self._vars[path] = v
            ttk.Checkbutton(parent, text=label, variable=v).pack(anchor="w", pady=1)

        eng = ttk.LabelFrame(f, text="Speech engine  (restart required)", padding=8)
        eng.pack(fill="x", pady=4)
        self._engine = tk.StringVar(value=c["engine"])
        self._wmodel = tk.StringVar(value=c["whisper_model"])
        if paths.is_frozen():
            # FlowClone.exe ships Parakeet only (faster-whisper would double the download).
            ttk.Label(eng, text="Engine: NVIDIA Parakeet — EN/CS/FR/ES + 21 more European "
                                "languages, auto-detected.").pack(anchor="w")
            ttk.Label(eng, text="The Whisper engine is available in the script install "
                                "(see README).", foreground="#666").pack(anchor="w", pady=(4, 0))
        else:
            row = ttk.Frame(eng); row.pack(fill="x")
            ttk.Label(row, text="Engine:").pack(side="left")
            ttk.Combobox(row, textvariable=self._engine, state="readonly", width=12,
                         values=["parakeet", "whisper"]).pack(side="left", padx=6)
            ttk.Label(row, text="Whisper model:").pack(side="left", padx=(14, 0))
            ttk.Combobox(row, textvariable=self._wmodel, state="readonly", width=16,
                         values=["tiny", "base", "small", "medium", "large-v3-turbo"]).pack(side="left", padx=6)
            ttk.Label(eng, text="Parakeet: EN/CS/FR/ES + 21 more European languages, auto-detected. "
                                "Whisper: 90+ languages.", foreground="#666").pack(anchor="w", pady=(4, 0))

        hot = ttk.LabelFrame(f, text="Hotkey  (restart required)", padding=8)
        hot.pack(fill="x", pady=4)
        self._hotkey = tk.StringVar(value="+".join(c["hotkeys"]["push_to_talk"]))
        row = ttk.Frame(hot); row.pack(fill="x")
        ttk.Label(row, text="Push-to-talk (hold):").pack(side="left")
        ttk.Entry(row, textvariable=self._hotkey, width=24).pack(side="left", padx=6)
        ttk.Label(hot, text='Keys joined with "+", e.g. ctrl+windows, f9, ctrl+alt',
                  foreground="#666").pack(anchor="w", pady=(4, 0))

        cl = ttk.LabelFrame(f, text="Cleanup", padding=8)
        cl.pack(fill="x", pady=4)
        check(cl, "Remove filler words (um, uh, ehm, euh…)", "cleanup", "remove_fillers")
        check(cl, 'Spoken punctuation commands ("period", "new line"…)', "cleanup", "spoken_punctuation")
        check(cl, '"Scratch that" deletes what you said before it', "cleanup", "scratch_that")
        check(cl, "Capitalize sentences", "cleanup", "capitalize")
        check(cl, "Drop trailing period in chat apps (Slack, WhatsApp…)",
              "cleanup", "drop_trailing_period_in_casual_apps")
        check(cl, '"Press enter" voice command auto-sends', "cleanup", "press_enter_command")

        llm = ttk.LabelFrame(f, text="AI polish (local Ollama — optional)", padding=8)
        llm.pack(fill="x", pady=4)
        check(llm, "Enable AI polish (needs Ollama running)", "llm", "enabled")
        check(llm, "Tone matching (casual in chat, formal in email)", "llm", "tone_matching")
        row = ttk.Frame(llm); row.pack(fill="x", pady=2)
        ttk.Label(row, text="Model:").pack(side="left")
        self._llm_model = tk.StringVar(value=c["llm"]["model"])
        ttk.Entry(row, textvariable=self._llm_model, width=20).pack(side="left", padx=6)

        gen = ttk.LabelFrame(f, text="General", padding=8)
        gen.pack(fill="x", pady=4)
        check(gen, "Sound cues on start/stop", "sounds")
        check(gen, "Show status pill while dictating", "overlay", "enabled")
        row = ttk.Frame(gen); row.pack(fill="x", pady=2)
        ttk.Label(row, text="Insertion:").pack(side="left")
        self._inject = tk.StringVar(value=c["injection"]["mode"])
        ttk.Combobox(row, textvariable=self._inject, state="readonly", width=8,
                     values=["paste", "type"]).pack(side="left", padx=6)
        self._autostart = tk.BooleanVar(value=is_autostart_enabled())
        ttk.Checkbutton(gen, text="Start FlowClone when Windows starts",
                        variable=self._autostart).pack(anchor="w", pady=1)

        ttk.Button(f, text="Save settings", command=self._save_settings).pack(anchor="w", pady=8)
        self._saved_label = ttk.Label(f, text="", foreground="#2a7a2a")
        self._saved_label.pack(anchor="w")
        return outer

    @staticmethod
    def _get(d, path):
        for p in path:
            d = d[p]
        return d

    @staticmethod
    def _set(d, path, value):
        for p in path[:-1]:
            d = d[p]
        d[path[-1]] = value

    def _save_settings(self):
        c = self.app.config
        restart_needed = (
            self._engine.get() != c["engine"]
            or self._wmodel.get() != c["whisper_model"]
            or self._hotkey.get() != "+".join(c["hotkeys"]["push_to_talk"])
        )
        c["engine"] = self._engine.get()
        c["whisper_model"] = self._wmodel.get()
        c["hotkeys"]["push_to_talk"] = [k.strip() for k in self._hotkey.get().split("+") if k.strip()]
        for path, var in self._vars.items():
            self._set(c, path, var.get())
        c["llm"]["model"] = self._llm_model.get().strip()
        c["injection"]["mode"] = self._inject.get()

        cfg.save_config(c)
        self.app.apply_live_settings()
        try:
            set_autostart(self._autostart.get())
        except OSError as e:
            messagebox.showwarning("Autostart", f"Could not update autostart: {e}")

        msg = "Saved."
        if restart_needed:
            msg += "  Engine/hotkey changes take effect after restarting FlowClone."
        self._saved_label.configure(text=msg)

    # ---------------- About ----------------

    def _about_tab(self, nb):
        from . import __version__
        f = ttk.Frame(nb, padding=16)
        ttk.Label(f, text="FlowClone", font=("Segoe UI", 16, "bold")).pack(anchor="w")
        ttk.Label(f, text=f"Version {__version__} — free, 100% local voice dictation.",
                  ).pack(anchor="w", pady=(0, 10))
        about = (
            "Hold Ctrl+Win anywhere, speak, release — polished text is inserted into\n"
            "the focused app. Nothing you say ever leaves this computer.\n\n"
            "Speech model: NVIDIA Parakeet-TDT-0.6B-v3 (CC-BY-4.0) via onnx-asr"
            + ("" if paths.is_frozen() else ",\nor faster-whisper (MIT)")
            + ". Optional AI polish: local Ollama.\n\n"
            f"Settings folder: {CONFIG_PATH.parent}\n"
            "Logs: settings folder → logs\\flowclone.log"
        )
        ttk.Label(f, text=about, justify="left").pack(anchor="w")
        return f
