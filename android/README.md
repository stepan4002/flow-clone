# FlowKey — Local Voice Keyboard for Android

A voice-only Android keyboard (IME) in the spirit of Wispr Flow — but **100% offline**.
Tap the mic, speak in **English, Czech, French, or Spanish** (or let it auto-detect),
and cleaned-up text is typed into whatever app has focus.

**Privacy by construction:** the app declares only the `RECORD_AUDIO` permission.
There is **no INTERNET permission at all**, so audio and text physically cannot leave
the device.

## Features

- Voice-only keyboard panel: big mic button, backspace (hold to repeat), enter, space,
  keyboard-switcher (globe), settings, and a language key (AUTO / EN / CS / FR / ES)
- Default engine: **multilingual Whisper** (sherpa-onnx `OfflineRecognizer`) —
  speak, then it transcribes when you stop (tap or auto-stop on silence)
- Optional engine: **English streaming Zipformer** (`OnlineRecognizer`) — live partial
  words while you speak, hands-free continuous dictation with automatic endpointing
- Text cleanup: filler-word removal (en/cs/fr/es), English spoken punctuation commands
  ("period", "comma", "question mark", "new line", …), "scratch that", capitalization,
  smart spacing
- Personal dictionary (always-fix corrections) and snippets (spoken trigger → expansion)
- Dictation history (last 50 transcripts, tap to copy, local only)
- Haptic + sound cues on start/stop (both can be disabled)
- Material 3 dark-mode-aware setup app, adaptive launcher icon

---

## Install the ready-made APK (no build needed)

On your phone, open
**https://github.com/stepan4002/flow-clone/releases/latest/download/FlowKey.apk**
(~285 MB, both models included: multilingual Whisper and the English streaming Zipformer), allow your browser/Files app to
install it, then open **FlowKey** and follow the 4 setup steps.

With USB debugging enabled you can also install it from a Windows PC in one line:

```powershell
irm https://raw.githubusercontent.com/stepan4002/flow-clone/main/scripts/install-android.ps1 | iex
```

---

## Quick build (developers)

```bash
cd android
./fetch-deps.sh            # downloads the sherpa-onnx AAR into app/libs/
./fetch-deps.sh --model    # optional: also bundle both speech models into the APK
./gradlew assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties`).

---

## Build & install — complete beginner path

### 0. What you need

- A Windows/macOS/Linux computer with ~10 GB free disk space
- An Android phone with Android 8.0+ (API 26+); a mid-range or better phone is
  recommended for Whisper
- A USB cable

### 1. Install Android Studio

Download from https://developer.android.com/studio and install with default options.
First launch downloads the Android SDK automatically.

### 2. Get the sherpa-onnx AAR (required)

1. Go to https://github.com/k2-fsa/sherpa-onnx/releases/latest
2. Under **Assets** download `sherpa-onnx-v1.12.x.aar` (exact version number varies;
   use v1.10.30 or newer)
3. Copy the file into this project's **`app/libs/`** folder

> Skipping this step causes build errors like
> `Unresolved reference: com.k2fsa.sherpa.onnx`.

### 3. Download the speech model

Follow **[MODELS.md](MODELS.md)** — short version:
download `sherpa-onnx-whisper-base.tar.bz2` (multilingual, NOT a `.en` variant), extract,
and keep the three files (`base-encoder.int8.onnx`, `base-decoder.int8.onnx`,
`base-tokens.txt`) ready for step 7.

### 4. Open and build the project

1. Android Studio → **Open** → select this `android` folder
2. Wait for Gradle sync to finish (first time downloads Gradle 8.14 + dependencies)
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
4. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`

### 5. Install on the phone

Easiest: enable **Developer options → USB debugging** on the phone, connect USB, and
press **Run ▶** in Android Studio. (Alternative: copy `app-debug.apk` to the phone and
open it; allow "install unknown apps".)

### 6. First launch

Open **FlowKey — Local Voice Keyboard**. The setup checklist walks you through:

1. **Speech model** — will say "not found" until step 7
2. **Microphone** — tap *Grant microphone*
3. **Enable keyboard** — opens system settings; turn on *FlowKey*
   (Android shows a standard warning for any third-party keyboard — FlowKey has no
   network access, so nothing can be transmitted)
4. **Switch keyboard** — shows the keyboard chooser; pick *FlowKey*

### 7. Copy the model to the phone

Copy the three Whisper files to
`Android/data/com.flowkey.ime/files/model/whisper/` (USB file transfer or `adb push` —
exact commands in [MODELS.md](MODELS.md)), then tap **Re-check** in FlowKey setup.
It should say **"Whisper model loaded ✓"** (first load takes a few seconds).

### 8. Dictate

1. Tap any text field, switch to FlowKey via the keyboard chooser (or the globe key on
   your current keyboard)
2. Tap the **mic** — it turns red and pulses
3. Speak. Stop talking for ~2.5 s (or tap the mic again) → "Transcribing…" → your text
   appears
4. The **language key** (bottom-left) cycles AUTO → EN → CS → FR → ES

---

## Using FlowKey

| Say (English) | Get |
|---|---|
| period / full stop | `.` |
| comma | `,` |
| question mark | `?` |
| exclamation mark / point | `!` |
| colon / semicolon | `:` / `;` |
| dash / hyphen | `-` |
| open quote … close quote | `"…"` |
| new line / new paragraph | line break / blank line |
| scratch that | deletes everything said so far in this utterance |

Notes:
- Spoken punctuation commands are **English-only** for now (Whisper already inserts
  normal punctuation itself in all languages).
- Filler words (um, uh, er, ehm, euh, este, pues …) are removed — can be disabled in
  settings.
- **Dictionary**: add "wrong → right" pairs in setup; applied to every dictation.
- **Snippets**: say the trigger phrase, get the expansion (e.g. "my address" → your
  full address).
- **History**: setup → History shows your last 50 transcripts; tap one to copy.

### Settings (in the setup app)

- Default language (AUTO/EN/CS/FR/ES) — same as the keyboard's language key
- Auto-stop after silence: on/off + duration (1.5–4 s)
- Haptic feedback and start/stop sound cue
- Spoken punctuation commands on/off; filler removal on/off
- **English streaming mode**: switches to the Zipformer streaming engine (live partial
  text, hands-free continuous dictation, English-only; the model ships in `FlowKey.apk`,
  or see MODELS.md §3 for builds without it)

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Build error `Unresolved reference: com.k2fsa...` | The AAR is missing — put `sherpa-onnx-v*.aar` into `app/libs/` and re-sync Gradle. |
| "Model files missing" | Files are in the wrong folder. They must be *inside* `Android/data/com.flowkey.ime/files/model/whisper/` on internal storage (not on SD card, not in Downloads). Use *Instructions → Copy path* in setup to get the exact path. Open the app once before copying so the folder exists. Then *Re-check*. |
| "Engine error: …" right after install | Usually a corrupt/partial model download, or mixing files from different models. Re-download and copy all three files again. `UnsatisfiedLinkError` means the AAR wasn't in `app/libs/` when the APK was built. |
| Mic button says "Grant microphone in setup" | Open setup → Grant microphone. If you previously chose "Don't allow", enable it in Android Settings → Apps → FlowKey → Permissions. |
| FlowKey not in the keyboard list | It must be *enabled* first: setup step 3 → toggle FlowKey on, then use the chooser (step 4). |
| Keyboard stops working after a while in background | Some OEMs (Xiaomi/MIUI, Huawei, Samsung "sleeping apps") kill background apps aggressively. Exclude FlowKey from battery optimization: Settings → Battery → uncheck/allow FlowKey. |
| Transcription is slow | Use `whisper-base` instead of `small`; close memory-heavy apps; make sure you copied the `int8` files. |
| First dictation after opening the keyboard is delayed | The model loads once per process ("Loading model…"); subsequent dictations are fast. |
| Auto-stop triggers while you think | Increase the silence duration in settings, or disable auto-stop (then only tapping the mic stops the take, max 60 s). |
| Streaming mode says model missing | You installed `FlowKey-no-model.apk` or your own build without bundled models. Install `FlowKey.apk`, or turn streaming mode off, or copy the Zipformer model into `.../model/zipformer/` — see MODELS.md §3. |

---

## Architecture

```
app/src/main/java/com/flowkey/ime/
├── VoiceInputIME.kt     InputMethodService. Keyboard panel, audio capture
│                        (AudioRecord, 16 kHz mono PCM → FloatArray), the two
│                        dictation session loops (Whisper take / streaming),
│                        composing-text partials, commit logic, haptics/sound.
├── RecognizerManager.kt Process-wide singleton owning the sherpa-onnx recognizer.
│                        StateFlow<State>: NotLoaded/Loading/Ready/ModelMissing/Error.
│                        Whisper language switching via OfflineRecognizer.setConfig.
├── ModelLocator.kt      Finds model files on disk (int8 preferred), copies bundled
│                        assets/model/** out on first run.
├── TextCleanup.kt       Pure post-processing: fillers, spoken punctuation commands,
│                        "scratch that", dictionary/snippets, capitalization, spacing.
├── Prefs.kt             SharedPreferences settings (language, engine, auto-stop,
│                        haptics/sound, cleanup toggles).
├── UserData.kt          Dictionary + snippets stored as JSON in SharedPreferences.
├── HistoryStore.kt      Last 50 transcripts, local-only JSON.
├── SetupActivity.kt     Onboarding checklist + settings + dictionary/snippets UI.
└── HistoryActivity.kt   Transcript list, tap-to-copy, clear-all.
```

Threading model: all audio reading and decoding runs on `Dispatchers.Default`
coroutines; every UI/InputConnection touch hops to `Dispatchers.Main`. Sessions are
cancelled and `AudioRecord`/streams released in `finally` blocks on
`onFinishInputView`/`onDestroy`. Recognizers are expensive and loaded once, off the main
thread, behind `RecognizerManager`.

Quality notes:
- No blank panel states: the status line always shows model-missing / loading (pulsing) /
  permission-missing / error, with an inline **Open setup** chip where action is needed.
- Errors are logged under tags `FlowKeyIME`, `FlowKeyRecognizer`, `FlowKeyHistory`
  (`adb logcat -s FlowKeyIME`); no silently swallowed exceptions on the happy paths.
- No network, no analytics, no accounts. Everything (settings, dictionary, snippets,
  history) lives in app-private storage.

## License / credits

- Speech recognition: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0)
- Models: OpenAI Whisper via k2-fsa conversions; Zipformer by k2-fsa
