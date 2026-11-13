# FlowKey — Local Voice Keyboard for Android

A voice-only Android keyboard (IME) in the spirit of Wispr Flow, but with speech
recognized **entirely on the phone**. Tap the mic and speak in **any of 99 languages**
(English, Czech, French and Spanish are preset; add any other in Setup) or let it
auto-detect. The words appear while you talk, and the cleaned-up text is typed into
whatever app has focus.

**Privacy by construction:** your audio is recognized on the device and never leaves it.
No analytics, no accounts. Since v1.2 the app holds the `INTERNET` permission for exactly
one feature: the optional higher-accuracy model, downloaded **only when you tap Download**
in Setup, from fixed HTTPS URLs on huggingface.co (redirected to its download servers),
with every file checked against a pinned SHA-256. Nothing else in the app connects anywhere.

## Features

- Voice-only keyboard panel: big mic button, backspace (hold to repeat), enter, space,
  keyboard switcher (globe), settings, and a language key (AUTO + your languages)
- **99 languages** with the built-in multilingual Whisper model (sherpa-onnx). Add or
  remove languages in Setup; no download needed
- **Live text in every language:** a voice detector (Silero VAD) splits your speech into
  phrases. Each phrase is transcribed while you keep talking, and the text shows live in
  the field
- Optional **English streaming mode** (Zipformer): the fastest live engine, English only
- **Spoken punctuation in 10 languages:** English, Czech, Slovak, French, Spanish, German,
  Italian, Portuguese, Polish and Dutch. The full lists are in [COMMANDS.md](COMMANDS.md)
- **Your words:** names, brands and jargon. FlowKey spells them your way in Whisper output
  (and fixes close misspellings), and the English streaming engine listens for them
  (hotwords)
- Optional extra: **higher-accuracy model** (Whisper small, ~375 MB in-app download)
- Text cleanup: filler-word removal, "scratch that", capitalization, smart spacing
- Personal dictionary (always-fix corrections) and snippets (spoken trigger → expansion)
- Dictation history (last 50 transcripts, tap to copy, local only)
- Haptic and sound cues on start/stop (both can be disabled)
- Material 3 setup app that follows dark mode, adaptive launcher icon

---

## Install the ready-made APK (no build needed)

On your phone, open
**https://github.com/stepan4002/flow-clone/releases/latest/download/FlowKey.apk**
(~270 MB, with both models: multilingual Whisper and the English streaming Zipformer).
Allow your browser or Files app to install it, then open **FlowKey** and follow the 4 setup
steps.

**Updating:** install the new `FlowKey.apk` over the old one. Settings, dictionary and
history are kept. Since v1.2, releases are signed with a dedicated release key that
officially takes over from the old one (APK key rotation), so updates from v1.0–v1.1
install in place.

With USB debugging enabled you can also install it from a Windows PC in one line:

```powershell
irm https://raw.githubusercontent.com/stepan4002/flow-clone/main/scripts/install-android.ps1 | iex
```

---

## Quick build (developers)

```bash
cd android
./fetch-deps.sh            # sherpa-onnx AAR -> app/libs/, Silero VAD -> assets/vad/
./fetch-deps.sh --model    # optional: also bundle both speech models into the APK
./gradlew testDebugUnitTest assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties`).

> Debug builds install as a **separate app, "FlowKey debug"** (`com.flowkey.ime.debug`),
> next to the released FlowKey. A debug build can't be installed over a release: the
> signatures differ.

---

## Build & install — complete beginner path

### 0. What you need

- A Windows/macOS/Linux computer with ~10 GB free disk space
- An Android phone with Android 8.0+ (API 26+). A mid-range or better phone is
  recommended for Whisper
- A USB cable

### 1. Install Android Studio

Download from https://developer.android.com/studio and install with default options.
First launch downloads the Android SDK automatically.

### 2. Get the build inputs (required)

Run `./fetch-deps.sh` from this folder (Git Bash on Windows). It downloads the
sherpa-onnx 1.12.14 AAR into **`app/libs/`** and the small voice detector into
**`app/src/main/assets/vad/`** (needed for live text).

> Without the AAR the build fails with `Unresolved reference: com.k2fsa.sherpa.onnx`.
> Without the voice detector the app still works, but live text is unavailable.

### 3. Download the speech model

Either run `./fetch-deps.sh --model` (bundles both models into the APK), or follow
**[MODELS.md](MODELS.md)**. Short version: download `sherpa-onnx-whisper-base.tar.bz2`
(multilingual, NOT a `.en` variant), extract it, and keep the three files
(`base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt`) ready for step 7.

### 4. Open and build the project

1. Android Studio → **Open** → select this `android` folder
2. Wait for Gradle sync to finish (the first time downloads Gradle 8.14 + dependencies)
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
4. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`

### 5. Install on the phone

Easiest: enable **Developer options → USB debugging** on the phone, connect USB, and
press **Run ▶** in Android Studio. Alternatively, copy `app-debug.apk` to the phone, open
it, and allow "install unknown apps". It installs as **FlowKey debug**.

### 6. First launch

Open **FlowKey**. The setup checklist walks you through:

1. **Speech model**: will say "not found" until step 7 (unless you bundled the models)
2. **Microphone**: tap *Grant microphone*
3. **Enable keyboard**: opens system settings; turn on *FlowKey*.
   Android shows a standard warning for any third-party keyboard. FlowKey never sends
   what you type or say anywhere.
4. **Switch keyboard**: shows the keyboard chooser; pick *FlowKey*

### 7. Copy the model to the phone

Copy the three Whisper files to
`Android/data/com.flowkey.ime/files/model/whisper/` (for a debug build:
`com.flowkey.ime.debug`), by USB file transfer or `adb push`. The exact commands are in
[MODELS.md](MODELS.md). Then tap **Re-check** in FlowKey setup.
It should say **"Whisper model loaded ✓"** (the first load takes a few seconds).

### 8. Dictate

1. Tap any text field and switch to FlowKey via the keyboard chooser (or the globe key on
   your current keyboard)
2. Tap the **mic**. It turns red and pulses
3. Speak. The words appear (underlined) as you go. Stop talking for ~2.5 s, or tap the mic
   again, and the text is finalized
4. The **language key** (bottom-left) cycles AUTO and the languages you enabled

---

## Using FlowKey

Spoken punctuation works in the language you dictate in. Some English examples:

| Say | Get |
|---|---|
| period / full stop | `.` |
| comma | `,` |
| question mark | `?` |
| exclamation mark / point | `!` |
| colon / semicolon | `:` / `;` |
| dash / hyphen · em dash | `-` · `—` |
| ellipsis | `…` |
| open quote … close quote | `"…"` |
| new line / new paragraph | line break / blank line |
| scratch that / delete that | deletes everything said so far in this dictation |

Czech, Slovak, French, Spanish, German, Italian, Portuguese, Polish and Dutch are listed in
**[COMMANDS.md](COMMANDS.md)**: "čárka", "nový řádek", "virgule", "punto y aparte",
"Komma", ….

Notes:
- With **AUTO**, the commands of the first language Whisper detects in a dictation apply.
- Words that are also ordinary words ("point", "punto", "Punkt"…) only count as
  punctuation at the end of what you said, or right before another command. So "à ce
  point" stays as you said it.
- Filler sounds are removed (hmm, uh, ehm everywhere; "um"/"er" in English, "euh" in
  French, "eh" in Czech and Slovak — never real words like Portuguese "um" or German "er").
  This can be turned off in settings.
- **Your words**: add names and terms in Setup. They get your exact spelling, close
  misspellings of longer words are fixed (can be turned off), and English streaming mode
  boosts them during recognition. To avoid "fixing" grammar, close-misspelling fixes are off
  in languages with case endings (Czech, Slovak, Polish, …), word forms with an added ending
  ("pipelines") are left alone, and short or single capitalized terms ("IT", "May") don't
  change ordinary words ("it", "may").
- **Dictionary**: "wrong → right" pairs, applied to every dictation. The "right" side also
  counts as one of your words.
- **Snippets**: say the trigger phrase, get the expansion (e.g. "my address" → your full
  address).
- **History**: Setup → History shows your last 50 transcripts; tap one to copy.

### Settings (in the setup app)

- **Languages**: add any of 99 languages to the language key, or remove them
- Default language: the same as the keyboard's language key
- Auto-stop after silence: on/off + duration (1.5–4 s)
- Haptic feedback and start/stop sound cue
- Spoken punctuation commands on/off; filler removal on/off
- **Live text** (Whisper): words appear while you speak. Turn it off to transcribe once
  at the end, which is lighter on slow phones
- **English streaming mode**: the Zipformer engine (fastest live text, English only, boosts
  your words). The model ships in `FlowKey.apk`; for builds without it, see MODELS.md §3
- **Extra: higher-accuracy model**: download Whisper small (~375 MB) and switch to it. It
  is 2–3× slower, and most useful for languages the built-in model handles less well.
  Delete it any time
- **Your words**, Dictionary, Snippets

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Build error `Unresolved reference: com.k2fsa...` | The AAR is missing. Run `./fetch-deps.sh` (or put `sherpa-onnx-1.12.14.aar` into `app/libs/`) and re-sync Gradle. |
| "Model files missing" | The files are in the wrong folder. They must be *inside* `Android/data/com.flowkey.ime/files/model/whisper/` on internal storage (not on an SD card, not in Downloads). Use *Instructions → Copy path* in setup to get the exact path. Open the app once before copying so the folder exists. Then *Re-check*. |
| "Engine error: …" right after install | Usually a corrupt or partial model download, or files mixed from different models. Re-download and copy all three files again. `UnsatisfiedLinkError` means the AAR wasn't in `app/libs/` when the APK was built. |
| Install fails with "App not installed" / `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | You are installing a build signed with a different key over the installed app (for example your own build over a release). Self-built debug APKs are a separate "FlowKey debug" app since v1.2. For anything else: uninstall FlowKey, then install. |
| Live text switch is greyed out | It needs the voice detector (`assets/vad/silero_vad.onnx`, fetched by `fetch-deps.sh`), and doesn't apply in English streaming mode, which is always live. |
| Text appears late on a slow phone | Live text re-transcribes the current phrase now and then. Turn **Live text** off to transcribe once at the end, or switch back from the higher-accuracy model. |
| "Download failed …" | Check the connection and tap **Resume download**. It continues where it stopped. "Not enough free space" needs ~430 MB free. |
| "Word boosting turned off" | The English streaming engine crashed while loading your words. Streaming keeps working without them; edit your words to try again. |
| Mic button says "Grant microphone in setup" | Open setup → Grant microphone. If you previously chose "Don't allow", enable it in Android Settings → Apps → FlowKey → Permissions. |
| FlowKey not in the keyboard list | It must be *enabled* first: setup step 3 → toggle FlowKey on, then use the chooser (step 4). |
| Keyboard stops working after a while in background | Some OEMs (Xiaomi/MIUI, Huawei, Samsung "sleeping apps") kill background apps aggressively. Exclude FlowKey from battery optimization: Settings → Battery → allow FlowKey. |
| First dictation after opening the keyboard is delayed | The model loads once per process ("Loading model…"); later dictations are fast. |
| Auto-stop triggers while you think | Increase the silence duration in settings, or disable auto-stop (then only tapping the mic stops). |
| Streaming mode says model missing | You installed `FlowKey-no-model.apk` or your own build without bundled models. Install `FlowKey.apk`, turn streaming mode off, or copy the Zipformer model into `.../model/zipformer/`, as described in MODELS.md §3. |

---

## Release signing (maintainers)

Releases are built and signed by two scripts (Windows PowerShell):

```powershell
powershell -ExecutionPolicy Bypass -File android\scripts\create-release-key.ps1   # once
powershell -ExecutionPolicy Bypass -File android\scripts\build-release.ps1        # every release
```

- `create-release-key.ps1` creates an RSA-4096 release key and an
  **APK Signature Scheme v3 rotation lineage** from the debug key that signed v1.0–v1.1.
  Everything is stored outside the repository.
- `build-release.ps1` builds `FlowKey.apk` and `FlowKey-no-model.apk`, signs both with the
  lineage (`--rotation-min-sdk-version 28`), verifies them, and writes `SHA256SUMS.txt`
  into `android/releases/<date>-v<version>/`.

**Back up these four files together** (password manager or encrypted drive). If any one is
lost, future updates need an uninstall on every phone:

| File | What it is |
|---|---|
| `%USERPROFILE%\.android\flowkey-release.jks` | the release key |
| `%USERPROFILE%\.android\flowkey-lineage.bin` | the debug → release rotation proof |
| `%USERPROFILE%\.android\debug.keystore` | the old key, still part of every release signature |
| `%USERPROFILE%\.gradle\flowkey-signing.properties` | the release key's password |

---

## Architecture

```
app/src/main/java/com/flowkey/ime/
├── VoiceInputIME.kt       InputMethodService. Keyboard panel, audio capture (AudioRecord,
│                          16 kHz mono), the dictation sessions (live Whisper with VAD,
│                          batch Whisper, streaming), composing text, commit logic.
├── RecognizerManager.kt   Process-wide singleton owning the sherpa-onnx recognizer
│                          (StateFlow<State>), Whisper base/small, streaming + hotwords,
│                          native-crash guards for streaming and for hotwords.
├── ModelLocator.kt        Finds model files on disk, copies bundled assets out on update,
│                          BPE vocab for hotwords.
├── ModelDownloader.kt     The only network code: optional Whisper small download
│                          (pinned SHA-256, resumable).
├── WhisperChunks.kt       Every Whisper decode, cut into ≤28 s chunks (Whisper's limit).
├── LivePolicy.kt          Live-text timing rules, in audio time.
├── AudioHistory.kt        Rolling audio buffer for phrase decodes.
├── TextCleanup.kt         Pure post-processing: fillers, commands, dictionary/snippets,
│                          capitalization, spacing.
├── PunctuationCommands.kt Spoken command tables for 10 languages + matcher helpers.
├── WordCorrector.kt       Applies "your words": exact casing + close-misspelling fixes.
├── Hotwords.kt            "Your words" → hotwords file for the English streaming engine.
├── Languages.kt           Whisper's 99 language codes, names, language-key cycle.
├── Prefs.kt               Settings (+ crash-guard flags in a separate, never-backed-up file).
├── UserData.kt            Dictionary, snippets and your words (JSON in SharedPreferences).
├── HistoryStore.kt        Last 50 transcripts, local-only JSON.
├── SetupActivity.kt       Onboarding checklist + all settings UI.
└── HistoryActivity.kt     Transcript list, tap-to-copy, clear-all.
```

Pure logic (cleanup, commands, languages, word correction, hotwords, live timing, download
decisions) is unit-tested on the JVM: `./gradlew testDebugUnitTest`, which also runs in CI.

Threading model: audio is read on its own coroutine into an unbounded channel, so slow
decoding never drops audio. Decoding runs on `Dispatchers.Default`, and every
UI/InputConnection call hops to `Dispatchers.Main`. Sessions are cancelled and
`AudioRecord`/VAD/streams released in `finally` blocks. Text is never committed into a
different field than the one a dictation started in. Recognizers are expensive; they are
loaded once, off the main thread, behind `RecognizerManager`.

Quality notes:
- No blank panel states: the status line always shows model-missing / loading (pulsing) /
  permission-missing / error, with an inline **Open setup** chip where action is needed.
- sherpa-onnx can terminate the process from native code on bad input (unknown language
  code, bad hotword config). Every such input is validated in Kotlin first, and model loads
  that could still crash are guarded by flags that switch the feature off after a crash.
- Errors are logged under the tags `FlowKeyIME`, `FlowKeyRecognizer`, `FlowKeyDownload` and
  `FlowKeyHistory` (`adb logcat -s FlowKeyIME FlowKeyRecognizer`).
- No analytics, no accounts. Settings, dictionary, snippets, your words and history live in
  app-private storage; Android backup skips the large models.

## License / credits

- Speech recognition: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0)
- Models: OpenAI Whisper via k2-fsa conversions; Zipformer by k2-fsa; Silero VAD (MIT)
