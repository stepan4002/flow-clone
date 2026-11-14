# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com/);
versioning: [SemVer](https://semver.org/).

## [1.3.0] — unreleased

### Windows — FlowClone
- **New app window** (Qt): Home with insights (words, streak, speaking speed, time saved, top
  apps, 30-day activity), **History** you can search, paste again and delete, Dictionary,
  Snippets, **Styles**, Settings and About — dark and light theme, follows Windows
- **Styles per app**: verbatim in code editors and terminals, casual in chat, formal in email,
  clean everywhere else — rules work offline; with AI on, the text is rewritten in the style
- **Command Mode**: select text, hold Ctrl+Win+Shift and say what to do ("make this shorter",
  "translate to Spanish"); with nothing selected it writes what you ask for
- **AI rewrite providers**: local Ollama, any OpenAI-compatible API (OpenAI, Groq, OpenRouter,
  LM Studio…) or Anthropic, with your own key stored in Windows Credential Manager; off by
  default, falls back to the rule-cleaned text on any error
- **First-run setup**: microphone check, a first dictation, where to find the tray icon
- New status pill with a live microphone waveform, and a new app icon
- Hand edits of dictionary.json / snippets.json apply on the next dictation
- Microphone choice in Settings; the hotkey can be recorded instead of typed
- The log no longer contains any dictated text
- Standalone **FlowClone-Setup.exe**: no Python needed, per-user install without admin
  rights, Start-menu entry, optional autostart, clean uninstall that asks before removing
  settings or the speech model
- Speech model download is resumable, pinned to one model version and SHA-256-verified,
  with progress on the status pill and tray tooltip; an existing Hugging Face cache copy is
  reused without extra disk space; tray → *Retry speech model download*
- Pressing the hotkey while the model is still downloading says so instead of recording
- Starting FlowClone again opens the running window instead of silently doing nothing
- Crashes in any thread are written to `logs\flowclone.log` / `logs\crash.log`
- `install.ps1` no longer removes or overwrites anything that belongs to an exe install

### Windows — FlowClone — fixes
- The model download no longer crashes on PCs with AVG/Avast web protection
  (their `SSLKEYLOGFILE` made Python's OpenSSL abort)
- Autostart now points at the right program after moving between script and exe installs
- "Open history file" works on a fresh install
- The speech model size is ~670 MB (was documented as ~330 MB)

### Project
- Windows UI moved from Tk / pystray / Pillow to Qt 6 (PySide6-Essentials, LGPL; notices and
  licence texts are shipped with the app)
- `windows-release.yml` builds, self-tests and attaches the installer for version tags;
  release checklist in `RELEASING.md`

## [1.2.0] — 2026-11-13

### Android — FlowKey
- Live text in every language: words appear while you speak (Silero voice detector +
  Whisper per phrase); the whole dictation stays editable until it ends
- Spoken punctuation commands in 10 languages: English, Czech, Slovak, French, Spanish,
  German, Italian, Portuguese, Polish, Dutch (list in `android/COMMANDS.md`)
- Languages: add any of Whisper's 99 languages to the language key in Setup, no download
- "Your words": names and jargon get your spelling, close misspellings are fixed, and the
  English streaming engine boosts them (hotwords)
- Optional higher-accuracy model (Whisper small, ~375 MB), downloaded in-app on request,
  resumable and SHA-256 checked. This is the only use of the new INTERNET permission
- Releases are signed with a dedicated release key (APK key rotation from the old debug
  key, so updates install in place); debug builds are a separate "FlowKey debug" app

### Android — FlowKey — fixes
- Dictations longer than ~30 s no longer lose their end (Whisper's input limit)
- English streaming mode no longer types its text in ALL CAPS
- Android backup works again: the large speech models no longer exceed the quota
- Whisper decodes short phrases faster (less zero padding)

### Project
- Android unit tests (JVM) run in CI
- `fetch-deps.sh` also downloads the voice detector; `tools/export_bpe_vocab.py`

## [1.1.1] — 2026-11-13

### Android — FlowKey — fixes
- Fixed a crash loop when "English streaming mode" is on: the bundled model is a
  Zipformer2 model, but the engine was told "zipformer", so sherpa-onnx exited the app
- Crash guard: if the streaming engine ever kills the app while loading, FlowKey turns
  streaming off on the next start, falls back to Whisper and explains why in setup
- Bundled models are copied atomically and refreshed once after each app update, so an
  interrupted copy can no longer leave a broken model file behind

## [1.1.0] — 2026-11-13

### Android — FlowKey
- `FlowKey.apk` now also bundles the English streaming Zipformer model, so
  "English streaming mode" works right after install with no manual model copy
- `fetch-deps.sh --model` downloads both models

## [1.0.0] — 2026-11-13

First public release.

### Windows — FlowClone
- Push-to-talk (Ctrl+Win) and hands-free (double-tap) dictation into any app
- Parakeet-TDT-0.6B-v3 engine (25 European languages, auto-detect) and optional Whisper engine (90+)
- Rule cleanup: fillers, spoken punctuation, "scratch that", capitalization, "press enter"
- Optional local AI polish via Ollama (self-corrections, per-app tone)
- Hub: stats, history, dictionary, snippets, settings, start-with-Windows
- One-line installer (`install.ps1`) with uninstall support

### Android — FlowKey
- Offline voice keyboard (IME) with multilingual Whisper base (EN/CS/FR/ES + auto)
- Optional English streaming Zipformer engine
- Dictionary, snippets, history, haptic/sound cues
- APK with bundled model published on GitHub Releases

### Project
- CI: byte-compile + unit tests (Python 3.10/3.12) and Android debug build
- Gradle wrapper, `fetch-deps.sh` for the AAR and model
