# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com/);
versioning: [SemVer](https://semver.org/).

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
