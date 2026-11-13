# Security & privacy

## Design guarantees

- **Android (FlowKey):** the only permission is `RECORD_AUDIO`. There is no `INTERNET`
  permission, so audio and text cannot leave the phone.
- **Windows (FlowClone):** network access is limited to the one-time speech-model
  download from Hugging Face, and — only if you enable AI polish — HTTP calls to your
  own Ollama server (`http://localhost:11434` by default).
- The Windows app reads keyboard state through a global hook to detect the push-to-talk
  hotkey. It does not log keystrokes.
- Transcripts are stored locally (`%APPDATA%\FlowClone\history.jsonl` and logs on
  Windows, private app storage on Android). Delete them at any time.

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Use GitHub's
[private vulnerability reporting](https://github.com/stepan4002/flow-clone/security/advisories/new)
instead. Expect a reply within a week.
