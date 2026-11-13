# Releasing FlowClone + FlowKey

One version number for the whole repository; every GitHub release carries **both** apps.

Why both: the download links in the README use `releases/latest/download/…`, which always
points at the newest published release. A release with only the Windows installer (or only
the APKs) would break the other app's link.

## Checklist

1. **Bump versions** (all the same `X.Y.Z`):
   - `windows/flowclone/__init__.py` → `__version__ = "X.Y.Z"` (required for every tag; the
     Windows workflow fails if the tag and this differ — also for Android-only changes);
   - `android/app/build.gradle.kts` → `versionCode` + 1, `versionName = "X.Y.Z"` (when the
     Android app changed);
   - `CHANGELOG.md` → a `## [X.Y.Z] — YYYY-MM-DD` section (a unit test checks it matches
     `__version__`).
2. **Tag and push**: `git tag vX.Y.Z && git push origin vX.Y.Z`.
   The *Windows release* workflow builds, tests and self-tests `FlowClone.exe`, builds
   `FlowClone-Setup.exe` and attaches it (+ `.sha256`) to a **draft** release for the tag.
3. **Android**: run `android\scripts\build-release.ps1` and upload `FlowKey.apk`,
   `FlowKey-no-model.apk` and `SHA256SUMS.txt` to the same draft release (if the Android app
   didn't change, re-upload the previous release's files).
4. **Publish** the draft. It becomes *Latest*, and both `latest/download` links work.

For a test build without a release, run the *Windows release* workflow manually
(*Actions → Windows release → Run workflow*); the installer is a workflow artifact.

## Building locally

- Windows: `windows\build_exe.bat` then `windows\build_installer.bat` (Python 3.12 with
  `pip install -r windows\requirements-build.txt`; Inno Setup 6).
- Android: see `android/README.md` → *Release signing*.
