# Contributing

Thanks for helping! FlowClone has one rule above all others: **it stays 100 % local**.
Please don't add cloud APIs, telemetry or analytics. Local, optional extras (like the
Ollama polish tier) are welcome.

## Getting set up

- **Windows app:** `cd windows && pip install -r requirements.txt && python run.py`
- **Android app:** `cd android && ./fetch-deps.sh && ./gradlew assembleDebug`
- **Tests:** `python -m pytest windows/tests` — please add a test when you change
  `cleanup.py` or `lexicon.py`.

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
