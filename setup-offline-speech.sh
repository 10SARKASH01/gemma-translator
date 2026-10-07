#!/bin/bash
# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != "--persian-quality" ] && [ "$1" != "--help" ]; }; then
    echo "Usage: ./setup-offline-speech.sh [--persian-quality]"
    exit 1
fi
if [ -f "${PROJECT_DIR}/speech.env" ]; then
    set -a
    source "${PROJECT_DIR}/speech.env"
    set +a
fi
export OFFLINE_SPEECH_DIR="${OFFLINE_SPEECH_DIR:-${PROJECT_DIR}/models/offline-speech}"
WHISPER_CPP_DIR="${OFFLINE_SPEECH_DIR}/whisper.cpp"
PYTHON_BIN="${PROJECT_DIR}/venv/bin/python3"
BUILD_WHISPER=0

if [ ! -x "$PYTHON_BIN" ]; then
    echo "[ERROR] Python venv missing. Run ./setup.sh first."
    exit 1
fi
if [ "${1:-}" = "--help" ]; then
    "$PYTHON_BIN" "${PROJECT_DIR}/backend/setup_speech.py" --help
    exit 0
fi

if [ -n "${WHISPER_CPP_BINARY:-}" ]; then
    if ! command -v "$WHISPER_CPP_BINARY" >/dev/null 2>&1; then
        echo "[ERROR] WHISPER_CPP_BINARY is not executable: ${WHISPER_CPP_BINARY}"
        exit 1
    fi
else
    export WHISPER_CPP_BINARY="${WHISPER_CPP_DIR}/build/bin/whisper-cli"
    if [ ! -x "$WHISPER_CPP_BINARY" ]; then
        BUILD_WHISPER=1
    fi
fi

if [ -n "${WHISPER_SERVER_BINARY:-}" ]; then
    if ! command -v "$WHISPER_SERVER_BINARY" >/dev/null 2>&1; then
        echo "[ERROR] WHISPER_SERVER_BINARY is not executable: ${WHISPER_SERVER_BINARY}"
        exit 1
    fi
else
    export WHISPER_SERVER_BINARY="${WHISPER_CPP_DIR}/build/bin/whisper-server"
    if [ ! -x "$WHISPER_SERVER_BINARY" ]; then
        BUILD_WHISPER=1
    fi
fi

# Older installations already contain whisper-cli. Build the persistent server
# too instead of skipping the entire build whenever the CLI is present.
if [ "$BUILD_WHISPER" -eq 1 ]; then
    for dependency in git cmake c++; do
        if ! command -v "$dependency" >/dev/null 2>&1; then
            echo "[ERROR] Missing ${dependency}. Run ./setup.sh (Debian), or install git, cmake and a C++ compiler."
            exit 1
        fi
    done
    mkdir -p "$OFFLINE_SPEECH_DIR"
    if [ ! -d "$WHISPER_CPP_DIR" ]; then
        git clone --depth 1 --branch v1.8.3 https://github.com/ggml-org/whisper.cpp.git "$WHISPER_CPP_DIR"
    fi
    cmake -S "$WHISPER_CPP_DIR" -B "${WHISPER_CPP_DIR}/build" \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
        -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
        -DWHISPER_BUILD_SERVER=ON -DWHISPER_CURL=OFF \
        -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_METAL=OFF
    cmake --build "${WHISPER_CPP_DIR}/build" --config Release \
        --target whisper-cli whisper-server --parallel "${WHISPER_BUILD_JOBS:-2}"
fi

"$PYTHON_BIN" "${PROJECT_DIR}/backend/setup_speech.py" "$@"
