# Free, fully-local STT tech choices for the clone (research, July 2026)

## TL;DR — chosen stacks

| Platform | Stack |
|---|---|
| **Windows** | **Parakeet-TDT-0.6B-v3 (ONNX int8) via `onnx-asr`** for STT + `keyboard` low-level hook for push-to-talk + clipboard-save → Ctrl+V paste → clipboard-restore injection + `sounddevice` mic capture + rule-based cleanup with optional **Ollama (qwen3:1.7b/4b)** polish. Fallback engine: **faster-whisper** (large-v3-turbo / small) for 90+ languages. |
| **Android** | **sherpa-onnx** (Apache-2.0, prebuilt Android AAR — no NDK build needed) inside a Kotlin **InputMethodService voice keyboard**. Streaming Zipformer (live partials) or Whisper/Moonshine offline models. Reference projects: Transcribro (ISC), Sayboard (GPL), sherpa-onnx android demos; FUTO Voice Input is UX-reference-only (restrictive license). |

## Windows engine comparison

| Engine | WER (EN) | CPU speed | Streaming | Size | License |
|---|---|---|---|---|---|
| **Parakeet-TDT-0.6B-v3 (ONNX)** | **6.3%** (beats Whisper large-v3's 7.4%) | 50–100x real-time; 5s utterance in ~100–300 ms | batch (so fast it feels live) | ~330 MB int8 | CC-BY-4.0 |
| faster-whisper (CTranslate2) | turbo ~7.5%, small ~9% | 2–5x real-time CPU | batch | 39M–809M | MIT |
| whisper.cpp | same models | similar CPU | batch + stream example | GGUF quant | MIT |
| OpenAI whisper (reference impl) | same | slowest — don't ship | batch | full fp32 | MIT |
| Distil-Whisper 3.5 | ~7.2% | 4–6x faster than large-v3 | batch | 756M | MIT (EN only) |
| Moonshine Voice v2 (Feb 2026) | 6.65% @245M | excellent, sub-200 ms | **true streaming** | 27M–245M | MIT |
| sherpa-onnx Zipformer | mid-single-digit | real-time on 1–2 cores | **true streaming** | 40–300 MB | Apache-2.0 |
| Vosk | 10–15% | very fast | streaming | 50 MB | Apache-2.0 (dated accuracy) |

**Why Parakeet wins for push-to-talk dictation**: the workload is batch (record 1–30 s,
transcribe on release). Parakeet transcribes 30 s of audio in <500 ms on a GPU-less laptop —
feels streaming without streaming complexity. Native punctuation + capitalization. 25 European
languages (v2 = English-only). The leading open-source Wispr clones (Handy — 22k stars,
OpenWhispr) already ship it. `pip install onnx-asr[cpu,hub]`, auto-downloads from HF.

**Watch**: Moonshine Voice (MIT, true streaming partials, official Windows/Android packages,
only ~5 months old) — strongest future challenger.

## Windows integration choices

- **Global push-to-talk hotkey**: `keyboard` lib (boppreh) — low-level SetWindowsHookEx hook,
  separate press/release events (essential for hold-to-talk), no admin needed on Windows.
  `pynput` = maintained alternative. Raw Win32 RegisterHotKey is wrong for hold (no release
  event). Caveat: hooks don't see keys when an elevated window is focused unless the app is
  elevated too.
- **Text injection** (battle-tested pattern from Handy/OpenWhispr):
  1. Primary: save clipboard → set transcript → simulate Ctrl+V → restore clipboard ~150 ms
     later. Works in ~95% of apps, perfect Unicode, fast for long text.
  2. Fallback: character-by-character `SendInput KEYEVENTF_UNICODE` typing for paste-blocking
     apps (some terminals, RDP). Expose as a setting.
  3. Avoid UI Automation TextPattern as primary (Electron/games/terminals don't implement it).
- **Mic capture**: `sounddevice` (PortAudio, NumPy-native, 16 kHz mono float32 callback).
  Add Silero VAD (~2 MB ONNX, MIT) later for auto-stop/trim if desired.
- **LLM cleanup**: rules first (fillers + spoken punctuation + scratch-that = ~80% of the
  value, 0 ms). Optional tier: Ollama at localhost:11434 with **qwen3:1.7b/4b** (Apache-2.0)
  or gemma3:4b-it-qat; 200–500 ms latency; degrade gracefully to rules-only when Ollama
  absent (OpenWhispr's approach).

## Android

| Option | Assessment |
|---|---|
| **sherpa-onnx Android** | Prebuilt AARs + example Kotlin apps (streaming ASR, VAD+ASR). Runs streaming Zipformer (live partials on 1–2 cores), Whisper, SenseVoice, Moonshine, Parakeet int8. Apache-2.0. **Chosen** — no NDK compilation needed. |
| whisper.cpp JNI | Best accuracy-per-MB, proven in shipped IMEs (Transcribro, FUTO), but you must build C++ with the NDK. small-q5_1 ≈190 MB ≈1–3 s for a 10 s utterance on mid-range. |
| Vosk Android | Easiest (Maven artifact) but clearly worse accuracy in 2026. Low-end fallback only. |
| FUTO Voice Input | Engine = whisper.cpp. Excellent UX to copy. **License = FUTO Source First — NOT open source, can't fork.** |
| Android built-in SpeechRecognizer | On-device mode (API 33+) exists but OEM-dependent, needs Google speech services, no model control. Not "fully local & free" in spirit. |

**IME structure (minimum viable)**: manifest `<service android:permission=
"android.permission.BIND_INPUT_METHOD">` + `<meta-data android:name="android.view.im">` +
RECORD_AUDIO. `class VoiceIME : InputMethodService()`, `onCreateInputView()` returns a panel
with mic button / backspace / switch-keyboard. AudioRecord 16 kHz mono → recognizer →
`currentInputConnection.commitText()`; streaming partials via `setComposingText()`.
Users enable it in Settings → System → Keyboards and switch via the keyboard-switcher icon.
Stay a voice-only IME users toggle to from their main keyboard (Transcribro/FUTO model).

**Phone model recommendations**

| Device tier | Model | Size |
|---|---|---|
| Low-end | whisper base-q5_1 / Vosk small | 60 / 50 MB |
| Mid-range (default) | **streaming Zipformer EN int8** or whisper small-q5_1 | ~100 / ~190 MB |
| Flagship | Parakeet-TDT-0.6B-v3 int8 via sherpa-onnx | ~330 MB |

## Model quick reference

| Model | Where | License |
|---|---|---|
| nemo-parakeet-tdt-0.6b-v3 (ONNX) | huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx (auto via onnx-asr) | CC-BY-4.0 |
| Whisper large-v3-turbo / small / base | Systran/faster-whisper-* on HF | MIT |
| Moonshine Voice | github.com/moonshine-ai/moonshine | MIT |
| sherpa-onnx streaming Zipformer EN | k2-fsa.github.io/sherpa/onnx/pretrained_models | Apache-2.0 |
| Silero VAD | snakers4/silero-vad | MIT |
| Qwen3-1.7B/4B (cleanup LLM) | `ollama pull qwen3:1.7b` | Apache-2.0 |

## Reference projects

- **Handy** — github.com/cjpais/Handy (MIT, Rust/Tauri, Parakeet via transcribe-rs) — the
  leading open-source Wispr clone.
- **OpenWhispr** — github.com/OpenWhispr/openwhispr (Electron, Whisper/Parakeet + Ollama).
- **Transcribro** — github.com/soupslurpr/Transcribro (ISC, Kotlin/Compose voice IME,
  whisper.cpp + Silero VAD).
- **Sayboard** — github.com/ElishaAz/Sayboard (GPL-3.0, Vosk voice IME, good IME plumbing
  reference).
- sherpa-onnx android examples — github.com/k2-fsa/sherpa-onnx.
