# Contributing

Thanks for helping! FlowClone has one rule above all others: **local by default**.
Speech recognition always runs on the device, and nothing is sent anywhere unless the user
turns it on. Cloud features are allowed only as opt-in extras that the user configures with
their own key (like the AI-rewrite providers) — never on by default. No telemetry, no
analytics, no accounts.

## Getting set up

- **Windows app:** `cd windows && pip install -r requirements.txt && python run.py`
- **Android app:** `cd android && ./fetch-deps.sh && ./gradlew assembleDebug`
- **Tests:** `python -m pytest windows/tests` — please add a test when you change
  `cleanup.py`, `lexicon.py`, `styles.py`, `llm.py`, `history.py` or `stats.py`. The UI lives
  in `windows/flowclone/ui/` (Qt); everything outside it must stay importable without Qt.
- **Screenshots of the Windows app:** `python windows/tools/screenshots.py OUT_DIR both`
  (demo data, temporary profile).

## Pull requests

1. Fork, then create a branch: `git checkout -b feat/short-description`
2. Keep changes focused. Use [Conventional Commits](https://www.conventionalcommits.org/)
   (`feat:`, `fix:`, `docs:` …).
3. Make sure CI is green (byte-compile, unit tests, Android build).
4. Multilingual support (EN / CS / FR / ES) must keep working. If you touch language
   handling, say how you tested it.

## Never commit

Models (`*.onnx`), AARs, APKs, keystores, `local.properties` or `.env` files —
`.gitignore` already blocks them. Large binaries ship through GitHub Releases.
