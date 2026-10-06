#!/bin/bash
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

cd "$(dirname "$0")"
if [ -f speech.env ]; then
    set -a
    source ./speech.env
    set +a
fi

# Fresh Raspberry Pi OS / Debian installs include the native speech engines,
# compiler and offline font coverage. Other platforms can provide these tools.
if command -v apt-get >/dev/null 2>&1; then
    APT_CMD=(apt-get)
    if [ "$(id -u)" -ne 0 ]; then APT_CMD=(sudo apt-get); fi
    "${APT_CMD[@]}" update
    "${APT_CMD[@]}" install -y python3-venv python3-pip libportaudio2 libasound2-dev \
        git cmake build-essential espeak-ng espeak-ng-data fonts-noto-core fonts-noto-cjk \
        netcat-openbsd lsof
fi

echo "Creating virtual environment..."
python3 -m venv venv

echo "Activating virtual environment..."
source venv/bin/activate

echo "Installing requirements..."
# This is a version-pinned requirements file, not a hash lockfile.
pip install --extra-index-url https://pypi.org/simple/ -r backend/requirements.txt

echo "Installing and preloading offline speech dependencies..."
bash ./setup-offline-speech.sh

echo "========================================="
echo "Setup complete!"
echo "Run ./download_model.sh to download the model."
echo "Run ./start.sh to start the servers."
echo "========================================="
