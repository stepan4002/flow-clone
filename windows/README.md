# FlowClone for Windows — free, 100% local voice dictation

Hold **Ctrl+Win**, speak, release — polished text appears at your cursor in **any app**
(browser, Slack, VS Code, terminal, Word...). Everything runs on your laptop:
no cloud, no account, no subscription, no word limits, works offline.

**Languages: English, Czech, French, Spanish** (and 21 more European languages) are
supported out of the box with automatic per-utterance language detection — just speak;
no switching needed.

## How it works

```
hold hotkey → mic capture (sounddevice, 16 kHz)
→ local speech-to-text (NVIDIA Parakeet-TDT-0.6B-v3, int8 ONNX — beats Whisper
  large-v3 accuracy, ~50-100x real-time on CPU, no GPU needed)
→ rule-based cleanup (fillers, spoken punctuation, "scratch that", capitalization)
→ optional AI polish via local Ollama (self-corrections, tone matching)
→ clipboard-save → Ctrl+V paste → clipboard-restore  (the same trick Wispr Flow uses)
```

## Install (5 minutes)

**One line** (PowerShell, no admin needed). This installs into `%LOCALAPPDATA%\FlowClone`,
creates a Start-menu shortcut and launches the app:

```powershell
irm https://raw.githubusercontent.com/stepan4002/flow-clone/main/install.ps1 | iex
```

**Manual install from a clone of this repo:**

1. Install **Python 3.10–3.12** from https://python.org (check "Add to PATH").
2. In this folder:
   ```
   pip install -r requirements.txt
   ```
3. Run it — double-click **`FlowClone.bat`** (no console window), or:
   ```
   python run.py
   ```
   First run downloads the speech model (~330 MB, one time, cached in
   `~/.cache/huggingface`). When the tray icon appears and "Loading speech model…"
   disappears, you're live.

Optional: `build_exe.bat` builds a standalone `FlowClone.exe` with PyInstaller so the
app runs without Python installed.

## Usage

| Action | How |
|---|---|
| Dictate | **Hold Ctrl+Win**, speak, release |
| Hands-free mode (no holding) | **Double-tap Ctrl+Win** (tap again or Esc to stop; 20-min cap) |
| Cancel a dictation | **Esc** while recording |
| Paste last transcript again | **Shift+Alt+Z** |
| Copy last transcript | **Shift+Alt+X** |
| Say **"press enter"** at the end | auto-sends the message after inserting |
| Say **"scratch that"** | deletes everything you said before it in this dictation |
| Spoken punctuation | "period", "comma", "question mark", "new line", "new paragraph"… |
| Open the **Hub** (settings, history, stats) | double-click the tray icon |
| Menu (hands-free, history, settings) | right-click the tray icon |

## The Hub

Double-click the tray icon to open the **FlowClone Hub** (the equivalent of Wispr's
Flow Hub):

- **Home** — dictation stats (today / 7 days / all-time words) and your recent
  transcripts (double-click to copy).
- **Dictionary** — words, corrections ("Eric" → "Erik"), replacements (shorthand →
  text). Applied instantly, no restart.
- **Snippets** — trigger phrase → expansion, applied instantly.
- **Settings** — engine, hotkey, cleanup toggles, AI polish, sound cues, insertion
  mode, and **"Start FlowClone when Windows starts"**. Most settings apply live;
  engine/hotkey changes need a restart.
- Logs live in `%APPDATA%\FlowClone\logs\flowclone.log`; a second copy of the app
  refuses to start (single-instance guard).

## Feature map vs Wispr Flow

| Wispr Flow feature | FlowClone | Notes |
|---|---|---|
| Push-to-talk Ctrl+Win, works in any app | ✅ | same default hotkey, same paste mechanism |
| Hands-free mode + 20-min cap | ✅ | double-tap or tray menu |
| Filler removal, auto-punctuation, capitalization | ✅ | Parakeet punctuates natively + rule layer |
| Backtrack: "scratch that" | ✅ rules | "at 2, actually 3" style needs the Ollama tier |
| Backtrack: restatement self-correction | ✅ with Ollama | see "AI polish" below |
| Tone matching per app (casual in Slack, formal in email) | ✅ | trailing-period drop in chat apps + LLM tone hint |
| Personal dictionary / corrections | ✅ | `%APPDATA%\FlowClone\dictionary.json` |
| Snippets ("insert my signature") | ✅ | `%APPDATA%\FlowClone\snippets.json` |
| Text replacements (shorthand → expansion) | ✅ | dictionary.json `replacements` |
| History of transcripts | ✅ | `history.jsonl` + paste-last/copy-last hotkeys |
| Flow Bar status pill | ✅ | minimal always-on-top pill, bottom-center |
| 100+ languages | ◐ 25 EU languages | incl. **EN/CS/FR/ES with auto-detect**; switch `"engine": "whisper"` for 90+ |
| Whispering support | ◐ | works reasonably; speak close to the mic |
| Command Mode / Transforms (voice editing) | ❌ (roadmap) | needs selected-text capture + LLM round-trip |
| Cloud sync, team features | ❌ by design | it's local — that's the point |
| Word limits / $15 per month | ✅ none / $0 | |

## Configuration

Everything lives in `%APPDATA%\FlowClone\` (auto-created on first run):

- **config.json** — hotkeys, engine, cleanup toggles, injection mode, casual-app list.
- **dictionary.json** — `words` (protect casing / bias recognition), `corrections`
  (`"Eric": "Erik"`), `replacements` (`"c30": "https://calendly.com/..."`).
- **snippets.json** — `"insert my signature": "Best regards,\nYour Name"` — say the
  trigger phrase while dictating.

Edit the files, then tray-menu → *Reload dictionary & snippets* (config.json changes
need a restart).

### Choosing the engine

- `"engine": "parakeet"` (default) — best accuracy + speed for English, Czech, French,
  Spanish and 21 other European languages. Auto-detects the language each dictation.
- `"engine": "whisper"` + `"whisper_model": "small"` — 90+ languages. Use
  `"large-v3-turbo"` if you have a decent GPU, `"base"` on a weak laptop. Set
  `"language": "cs"` etc. to pin a language, or `null` for auto-detect.

### AI polish (optional but recommended — still 100% local)

Adds restatement self-correction ("meet at 5… no wait, 6" → "meet at 6"), grammar
smoothing, spoken lists → formatted lists, per-app tone. Costs ~0.3–1 s extra.

1. Install Ollama: https://ollama.com/download
2. `ollama pull qwen3:1.7b`  (or `qwen3:4b` for better quality)
3. In config.json set `"llm": { "enabled": true, ... }` and restart.

If Ollama isn't running, FlowClone silently falls back to rule-based cleanup.

### Injection modes

Default is clipboard-paste (fast, reliable). Apps that block Ctrl+V (some terminals,
RDP) can be listed in `injection.type_mode_apps` (e.g. `["mstsc.exe"]`) to use
per-character typing instead.

## Auto-start with Windows

Open the Hub (double-click the tray icon) → **Settings** → tick
**Start FlowClone when Windows starts**. This adds a per-user entry under
`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`; untick it to remove it.

## Troubleshooting

- **Nothing types / hotkey dead in some window** → that window runs as admin; run
  FlowClone from an admin terminal too.
- **Start menu pops up sometimes** → release Ctrl last (or change the hotkey to
  `["f9"]` in config.json — any keys work, e.g. `["ctrl", "alt"]`).
- **Slow first transcription** → the model warms up once after start; subsequent
  dictations are ~0.3–0.8 s.
- **Wrong mic** → set `"audio": {"device": N}`; list devices with
  `python -c "import sounddevice; print(sounddevice.query_devices())"`.
- **Paste lands in the wrong app** → the text goes to whatever is focused when you
  release the hotkey; don't Alt-Tab mid-dictation.
- **Czech/French/Spanish comes out as English words** → very short utterances can
  fool language detection; speak a full sentence, or pin the language (Whisper engine:
  `"language": "cs"`).

## Code layout

```
run.py                  entry point
flowclone/
  app.py                orchestrator + push-to-talk state machine
  config.py             defaults + %APPDATA% config loading
  audio.py              sounddevice recorder
  stt.py                Parakeet (onnx-asr) + Whisper (faster-whisper) engines
  cleanup.py            rule-based polish (fillers, punctuation, scratch-that…)
  lexicon.py            dictionary / corrections / replacements / snippets
  llm.py                optional Ollama polish tier
  inject.py             clipboard-paste / type injection
  hotkeys.py            global hold-combo hook (keyboard lib)
  foreground.py         focused-app detection (tone matching)
  overlay.py            "Flow Bar" status pill (tkinter)
  tray.py               system tray icon + menu (pystray)
  history.py            JSONL dictation history
```

## Licenses

All components are free: Parakeet model CC-BY-4.0 (attribution: NVIDIA), onnx-asr /
faster-whisper MIT, sherpa/keyboard/sounddevice etc. MIT/Apache-2.0/BSD. Qwen3 Apache-2.0.
