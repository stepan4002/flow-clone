# FlowKey — Model & AAR Downloads

FlowKey runs speech recognition **on-device** with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

**Using the release `FlowKey.apk`? You need nothing from this page:** it already contains
both speech models and the voice detector. The optional higher-accuracy model (Whisper small)
is downloaded from inside the app (Setup → *Extra: higher-accuracy model*).

This page is for building FlowKey yourself, or for `FlowKey-no-model.apk`:

1. The **sherpa-onnx Android library (AAR)** and the **Silero voice detector**, needed to
   *build* the app. `./fetch-deps.sh` downloads both.
2. The **speech model files**, needed to *run* the app (bundled with
   `./fetch-deps.sh --model`, or copied to the phone).

---

## 1. The sherpa-onnx AAR (required to build)

1. Open the latest release page: **https://github.com/k2-fsa/sherpa-onnx/releases/latest**
2. Under **Assets**, download the file named like:
   `sherpa-onnx-v1.12.x.aar`
   (any recent version works; use **v1.10.30 or newer** so runtime language switching via
   `OfflineRecognizer.setConfig` is available)
3. Copy it into the project at: **`app/libs/`**
4. In Android Studio: **File → Sync Project with Gradle Files**

The build script picks up any `*.aar` in `app/libs/` automatically. FlowKey is tested with
**1.12.14** (what `fetch-deps.sh` downloads).

### Voice detector for live text (Silero VAD, ~0.6 MB)

`fetch-deps.sh` puts
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
at **`app/src/main/assets/vad/silero_vad.onnx`**. Without it the app builds and works, but
**Live text** is unavailable: Whisper then transcribes once after you stop.

---

## 2. Default engine model: multilingual Whisper (99 languages + auto)

FlowKey's default engine is OpenAI Whisper (multilingual) running through sherpa-onnx's
`OfflineRecognizer`. **Do NOT download a `.en` variant** — those are English-only and
cannot do Czech/French/Spanish.

Pick ONE:

| Model | Download | Size (int8) | Recommendation |
|---|---|---|---|
| **whisper-base** (multilingual) | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2 | ~80 MB | Start here. Good quality, works on mid-range phones. |
| **whisper-small** (multilingual) | Use **Setup → Extra: higher-accuracy model** in the app | ~375 MB | Better quality, 2–3× slower. The app downloads it to `files/model/whisper-small/` and checks every file's SHA-256. |

More models: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html

### Which files you need (3 files)

Extract the `.tar.bz2` (7-Zip on Windows, `tar xjf` on macOS/Linux). From the extracted
folder copy **only these three files** (example names for `base`):

- `base-encoder.int8.onnx`
- `base-decoder.int8.onnx`
- `base-tokens.txt`

(For `small` they are `small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt`.
The non-int8 `.onnx` files also work but are bigger and slower — FlowKey automatically
prefers `int8` files when both are present.)

### Where to put them on the phone

Install and open the FlowKey app once first (this creates the folder), then copy the three
files to:

```
Android/data/com.flowkey.ime/files/model/whisper/
```

**Option A — USB cable (no tools needed):**
1. Connect the phone via USB, choose "File transfer" on the phone.
2. In Windows Explorer / Android Studio Device Explorer, navigate to
   `This PC → <your phone> → Internal storage → Android → data → com.flowkey.ime → files → model → whisper`
   (create the `model` and `whisper` folders if they don't exist).
3. Copy the three files there.

**Option B — adb:**
```bash
adb shell mkdir -p /sdcard/Android/data/com.flowkey.ime/files/model/whisper
adb push base-encoder.int8.onnx  /sdcard/Android/data/com.flowkey.ime/files/model/whisper/
adb push base-decoder.int8.onnx  /sdcard/Android/data/com.flowkey.ime/files/model/whisper/
adb push base-tokens.txt         /sdcard/Android/data/com.flowkey.ime/files/model/whisper/
```

Then open FlowKey setup and tap **Re-check**.

### Option C — bundle the model inside the APK

If you prefer a single self-contained APK, put the same three files into the project at:

```
app/src/main/assets/model/whisper/
```

and build. On first run the app copies them out to the external files dir. Note the APK
becomes correspondingly large (and Android Studio installs get slower).

---

## 3. Optional engine model: English streaming Zipformer (live partial words)

**Already included in the release `FlowKey.apk`** — skip this section unless you use
`FlowKey-no-model.apk` or your own build. (`./fetch-deps.sh --model` bundles it for you.)

Only needed if you enable **"English streaming mode"** in FlowKey settings. English-only,
but shows words as you speak and supports hands-free continuous dictation with automatic
sentence endpointing.

Download:
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2

From the extracted folder copy **four files**:

- `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx`
- `decoder-epoch-99-avg-1-chunk-16-left-128.onnx`
- `joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx`
- `tokens.txt`

To:

```
Android/data/com.flowkey.ime/files/model/zipformer/
```

(or bundle at `app/src/main/assets/model/zipformer/`).

### Hotwords ("Your words" in the streaming engine)

The streaming engine boosts the user's words with sherpa-onnx hotwords. For this BPE model
that needs a `bpe.vocab`, which the model does not ship. FlowKey commits one at
`app/src/main/assets/hotwords/en-2023-06-26.bpe.vocab`, exported from the model's
`bpe.model` with `tools/export_bpe_vocab.py`. If you replace the streaming model with
another BPE model, FlowKey notices the mismatch (the vocab must match `tokens.txt`) and
simply runs without hotwords. Regenerate the vocab with
`python tools/export_bpe_vocab.py <model>/bpe.model app/src/main/assets/hotwords/en-2023-06-26.bpe.vocab`.

---

## 4. How FlowKey finds the files

- Whisper: looks in `files/model/whisper/`, then in `files/model/` itself. The downloaded
  higher-accuracy model lives in `files/model/whisper-small/`; whichever one is selected in
  Setup is preferred, and the other one is the fallback.
- Zipformer: looks in `files/model/zipformer/`, then in `files/model/` (a `joiner` file is
  what marks a folder as a Zipformer model).
- File names don't have to match exactly — the app picks the `.onnx` file whose name
  contains `encoder` / `decoder` / `joiner`, preferring `int8` variants, plus a `*tokens*.txt`.

## 5. Upgrade ideas

- **whisper-medium** (multilingual): noticeably better accuracy, but slow on most phones.
- **Per-language streaming models** (French, Spanish, Korean, …) from the sherpa-onnx
  asr-models release: faster live text than Whisper for that one language.
- **NVIDIA Parakeet / NeMo transducer int8** (English, flagship phones): supported by
  sherpa-onnx via `OfflineRecognizerConfig(modelConfig = OfflineModelConfig(transducer=...))`
  — requires a small code change in `RecognizerManager.whisperConfig()` (different model
  config block), not just a file swap.
- Any other model from the sherpa-onnx pretrained-models page that matches the API used
  here (Whisper offline, or online transducer for streaming).
