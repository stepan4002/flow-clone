#!/usr/bin/env bash
# Downloads the large build inputs that are NOT stored in git:
#   - the sherpa-onnx Android AAR       -> app/libs/          (required to build)
#   - the Silero voice activity detector  -> app/src/main/assets/vad/ (live text; ~0.6 MB)
#   - the multilingual Whisper base model -> app/src/main/assets/model/whisper/
#   - the English streaming Zipformer     -> app/src/main/assets/model/zipformer/
#     (both optional: only with --model, to build an APK that ships with the models)
#
# Usage (Linux, macOS, or Git Bash on Windows), from the android/ folder:
#   ./fetch-deps.sh            # AAR + VAD
#   ./fetch-deps.sh --model    # AAR + VAD + bundled speech models
set -euo pipefail

SHERPA_VERSION="1.12.14"
cd "$(dirname "$0")"

aar="app/libs/sherpa-onnx-${SHERPA_VERSION}.aar"
if [ ! -f "$aar" ]; then
  echo "==> Downloading sherpa-onnx ${SHERPA_VERSION} AAR"
  curl -fL --retry 3 -o "$aar" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
else
  echo "==> $aar already present"
fi

vad="app/src/main/assets/vad/silero_vad.onnx"
if [ ! -f "$vad" ]; then
  echo "==> Downloading Silero VAD (live text)"
  mkdir -p "$(dirname "$vad")"
  curl -fL --retry 3 -o "$vad" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
else
  echo "==> $vad already present"
fi

if [ "${1:-}" = "--model" ]; then
  dest="app/src/main/assets/model/whisper"
  if [ -f "$dest/base-encoder.int8.onnx" ]; then
    echo "==> Model already present in $dest"
  else
    echo "==> Downloading multilingual Whisper base model (~200 MB)"
    tmp="$(mktemp -d)"
    curl -fL --retry 3 -o "$tmp/whisper-base.tar.bz2" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
    tar -xjf "$tmp/whisper-base.tar.bz2" -C "$tmp"
    mkdir -p "$dest"
    cp "$tmp"/sherpa-onnx-whisper-base/base-encoder.int8.onnx \
       "$tmp"/sherpa-onnx-whisper-base/base-decoder.int8.onnx \
       "$tmp"/sherpa-onnx-whisper-base/base-tokens.txt "$dest/"
    rm -rf "$tmp"
    echo "==> Model copied to $dest"
  fi

  zdest="app/src/main/assets/model/zipformer"
  zname="sherpa-onnx-streaming-zipformer-en-2023-06-26"
  if [ -f "$zdest/tokens.txt" ]; then
    echo "==> Streaming model already present in $zdest"
  else
    echo "==> Downloading English streaming Zipformer model (~310 MB download, ~70 MB kept)"
    tmp="$(mktemp -d)"
    curl -fL --retry 3 -o "$tmp/zipformer.tar.bz2"       "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${zname}.tar.bz2"
    tar -xjf "$tmp/zipformer.tar.bz2" -C "$tmp"
    mkdir -p "$zdest"
    # int8 encoder/joiner + fp32 decoder: the combination sherpa-onnx recommends on Android
    cp "$tmp/$zname"/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx        "$tmp/$zname"/decoder-epoch-99-avg-1-chunk-16-left-128.onnx        "$tmp/$zname"/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx        "$tmp/$zname"/tokens.txt "$zdest/"
    rm -rf "$tmp"
    echo "==> Streaming model copied to $zdest"
  fi
fi

echo "Done. Build with: ./gradlew assembleDebug"
