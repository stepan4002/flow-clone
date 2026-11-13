#!/usr/bin/env bash
# Downloads the large build inputs that are NOT stored in git:
#   - the sherpa-onnx Android AAR       -> app/libs/          (required to build)
#   - the multilingual Whisper base model -> app/src/main/assets/model/whisper/
#     (optional: only with --model, to build an APK that ships with the model)
#
# Usage (Linux, macOS, or Git Bash on Windows), from the android/ folder:
#   ./fetch-deps.sh            # AAR only
#   ./fetch-deps.sh --model    # AAR + bundled model
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
fi

echo "Done. Build with: ./gradlew assembleDebug"
