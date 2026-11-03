#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DISTILLPOD_FIXTURES="${1:?Pass a fixture output directory}"
mkdir -p "$DISTILLPOD_FIXTURES"
for DISTILLPOD_MODEL in tiny.en tiny; do
    DISTILLPOD_FILE="$DISTILLPOD_FIXTURES/ggml-$DISTILLPOD_MODEL.bin"
    if [[ "$DISTILLPOD_MODEL" == tiny.en ]]; then
        DISTILLPOD_SHA=921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f
    else
        DISTILLPOD_SHA=be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
    fi
    if ! printf '%s  %s\n' "$DISTILLPOD_SHA" "$DISTILLPOD_FILE" | sha256sum --check --status; then
        curl --fail --location --retry 3 "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$DISTILLPOD_MODEL.bin" -o "$DISTILLPOD_FILE"
    fi
    printf '%s  %s\n' "$DISTILLPOD_SHA" "$DISTILLPOD_FILE" | sha256sum --check
done
cp third_party/whisper.cpp/samples/jfk.wav "$DISTILLPOD_FIXTURES/jfk.wav"
cp third_party/whisper.cpp/samples/jfk.mp3 "$DISTILLPOD_FIXTURES/jfk.mp3"
