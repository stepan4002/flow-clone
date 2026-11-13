# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com/);
versioning: [SemVer](https://semver.org/).

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
