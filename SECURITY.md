# Security & privacy

## Design guarantees

- **Android (FlowKey):** speech is recognized entirely on the phone and audio never leaves
  it. Since v1.2 the app has the `INTERNET` permission for exactly one feature: the optional
  higher-accuracy model, downloaded only when you tap Download in Setup. That code
  (`ModelDownloader.kt`) fetches fixed HTTPS URLs on huggingface.co (which redirect to
  Hugging Face's download servers) and rejects any file whose SHA-256 does not match the
  pinned hash. The model is stored in the app's internal storage, out of reach of other apps.
  Nothing else in the app opens a connection.
- **Android releases** are signed with a dedicated release key (APK Signature Scheme v3, with
  key rotation from the debug key that signed v1.0–v1.1). Debug builds are a separate app
  (`com.flowkey.ime.debug`).
- **Windows (FlowClone):** network access is limited to the one-time speech-model
  download from Hugging Face — pinned to one exact model revision, every file checked
  against a SHA-256 in the source code (`model_store.py`) — and, only if you enable AI
  polish, HTTP calls to your own Ollama server (`http://localhost:11434` by default).
- **Windows installer:** `FlowClone-Setup.exe` is not code-signed yet. Each release
  publishes `FlowClone-Setup.exe.sha256`; compare it with
  `Get-FileHash .\FlowClone-Setup.exe -Algorithm SHA256`. The installer is built by GitHub
  Actions from the tagged source (`.github/workflows/windows-release.yml`).
- The Windows app reads keyboard state through a global hook to detect the push-to-talk
  hotkey. It does not log keystrokes.
- Transcripts are stored locally (`%APPDATA%\FlowClone\history.jsonl` and logs on
  Windows, private app storage on Android). Delete them at any time.

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Use GitHub's
[private vulnerability reporting](https://github.com/stepan4002/flow-clone/security/advisories/new)
instead. Expect a reply within a week.
