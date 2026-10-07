<img width="960" height="540" src="https://storage.googleapis.com/experiments-uploads/gemma-translator/gemma-translator.gif" />

# Gemma Translator

This repo was built with the assistance of [Google Antigravity](https://antigravity.google/) and includes code to run an on-device, fully offline voice translator powered by [Gemma 4](https://ai.google.dev/gemma/docs/core) and [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-lm). This project features a web frontend optimized for small handheld displays (e.g., 480x320) and a Python API server (`http.server`) that communicates with Gemma. Text-to-speech is powered by [Moonshine](https://github.com/moonshine-ai/moonshine).

https://github.com/user-attachments/assets/343072ce-dc78-44a7-a783-99312845cabe

## Features

- **On-Device Inference**: Uses LiteRT-LM to run the `gemma4-e2b` model entirely locally. No internet required after setup.
- **Voice Interface**: Captures microphone audio, processes it, and sends it to the local model.
- **Optimized UI**: Retro-terminal styling custom-built for small hardware screens (like Raspberry Pi displays).
- **Touch controls**: Tap the language arrows and hold either person's on-screen talk button. The same interface also supports mouse and keyboard input.
- **Unified Startup**: One script to launch the LLM server, the Python API, and the React frontend.
- **Persian and Urdu**: Offline voice input/output in either lane, with RTL text. Persian/Urdu STT uses multilingual whisper.cpp; Persian TTS uses Piper, and Urdu TTS uses eSpeak NG. Existing languages keep Moonshine.
- **French**: French (`fr`) works in both directions using the shared Whisper recognizer, Gemma translation, and Moonshine's French voice.
- **Pipeline performance**: A shared resident Whisper model, shorter Gemma prompt, startup warmup, and speech chunk prefetch reduce repeated work. See [configuration and stage timings](docs/PERFORMANCE.md).
- **Persian accuracy**: Improved audio capture, an optional stronger local recognition model, and separate speech/text checks. See [setup and verification](docs/PERSIAN_ACCURACY.md).
- **Translation quality**: Meaning-based instructions guide natural Persian phrasing and idioms. See the [local Persian translation comparison](docs/TRANSLATION_QUALITY.md) to check your installed Gemma model.
- **Persian recognition speed**: Settings can compare accurate and faster recognition on the same microphone recording. See [the Pi recognition guide](docs/PERSIAN_STT_PERFORMANCE.md) before selecting the faster mode.

## Prerequisites

- Python 3.10+
- Node.js 18+ (20 LTS recommended) & npm — installed automatically by `deploy-pi.sh` on Raspberry Pi OS / Debian
- Linux or macOS

## Required Hardware

- **Compute**: Raspberry Pi 5 with 8GB RAM
- **Audio Input**: Microphone or USB audio capture interface
- **Audio Output**: Speaker or headphone output device
- **Display**: Display monitor or touchscreen (e.g., 480x320 kiosk display)

<img width="3024" height="1672" src="https://storage.googleapis.com/experiments-uploads/gemma-translator/gemma-translator-cad.gif" />

## Setup Instructions

1. **Make Scripts Executable**
   Ensure the setup, download, start, and deployment scripts have execute permissions:
   ```bash
   chmod +x setup.sh download_model.sh start.sh deploy-pi.sh
   ```

2. **Install Dependencies**
   Run the setup script to create a Python virtual environment (`venv`) and install all required packages:
   ```bash
   ./setup.sh
   ```
   On Raspberry Pi OS / Debian, setup also installs native speech dependencies,
   builds whisper.cpp, downloads its multilingual model and the Persian Piper
   voice, and preloads all six existing Moonshine languages plus the French
   Moonshine voice for offline use.
   Setup needs internet access; inference does not. See the
   [Persian/Urdu setup and verification guide](docs/OFFLINE_LANGUAGES.md) for
   custom paths, reuse of an existing Whisper model, and offline checks.

3. **Download the Model**
   Run the model downloader script to fetch the `gemma4-e2b` model from Hugging Face and import it into LiteRT-LM:
   ```bash
   ./download_model.sh
   ```

## Running the Application

Start all services (LiteRT-LM, the Python API server, and the Vite Web UI) in development mode:
```bash
./start.sh
```

To run in production mode (skipping Vite dev server and serving compiled UI assets from `frontend/dist/` via `backend/server.py` on port 3000):
```bash
./start.sh --prod
```

The application will be accessible at:
- **Web UI (Dev)**: `http://localhost:5173`
- **Web UI (Prod) / API server**: `http://localhost:3000`
- **LiteRT-LM**: `http://localhost:9379`

## Raspberry Pi Appliance Deployment

To deploy as a permanent systemd kiosk service on a Raspberry Pi 5 (8GB):
```bash
./deploy-pi.sh
```
This automated script installs Debian audio/venv packages, sets up the Python environment, builds production UI assets, downloads the LiteRT model, registers the systemd unit from `deploy/gemma-translator.service`, and configures LXDE GUI autostart (`~/.config/lxsession/rpd-x/autostart`) to launch Chromium in kiosk mode pointing to `http://localhost:3000`.

For Persian/Urdu, no manual recording or extra runtime server is required.
Select either language with the revolver arrows or keys and hold/release that
person's talk button or recording key. Enable Speech Output in settings to hear the result.
Copy `speech.env.example` to `speech.env` before setup if you need custom
executable/model paths; the same settings are read by startup and systemd.

## Project Structure

- `frontend/` - React (Vite) web frontend (`index.html`, `src/`, styles, and Vite configuration).
- `backend/` - Python API server (`server.py` and `requirements.txt`) for Moonshine STT, moonshine-voice TTS, and model proxying.
- `deploy/` - Parameterizable systemd service unit template (`gemma-translator.service`).
- `stl/` - STL files for 3D printing the hardware case.
- `setup.sh` - Automates Python virtual environment creation and dependency installation.
- `download_model.sh` - Fetches the required LiteRT model.
- `start.sh` - Multi-process launcher supporting `--prod` and development modes.
- `deploy-pi.sh` - One-command Raspberry Pi automated deployment script.

## Touchscreen Controls

Both keyboard modes also support touch, without changing any settings:

- Tap **◀ / ▶** beside either language to switch that person's language. The two people always have different languages.
- **Hold to talk** under your language, speak, then lift your finger to transcribe, translate, and speak the result in the other person's language. Holding the other button reverses the direction.
- Allow microphone access on the first use. Wait for **Release to translate** before speaking; a quick tap released during microphone setup is discarded.
- You can slide your finger off the button while holding; lifting it still ends the recording. Interrupted touches, opening Settings, or leaving the window cancel the recording.
- Tap **⚙** for settings. Scroll the results or settings with your finger when the text is long.

The layout fills the screen, including 480×320 displays, with 44-pixel language and talk controls. Use Chromium on the Pi at `http://localhost:3000` (production) or `http://localhost:5173` (development) so microphone access works. Access from another device requires HTTPS.

After updating an existing installation, rebuild the frontend before restarting production mode:

```bash
git pull --ff-only
npm --prefix frontend run build
./start.sh --prod
```

## Keyboard Shortcuts

The Gemma Translator supports **two keyboard modes**. Switch between them anytime from the **Settings panel → "Keyboard Mode"** dropdown. The choice is remembered across restarts (stored in the browser's `localStorage` under the key `keyboardMode`).

The app has two lanes (two people facing each other on the kiosk):
- **Lane 1 / Person 1** — the left/top lane.
- **Lane 2 / Person 2** — the right/bottom lane.

Each lane has a rotating language "revolver" and records speech, which is transcribed locally (Moonshine or Whisper), translated by Gemma, and spoken in the other lane's language using the corresponding local voice.

### Landscape Mode (default) — "active person"
One lane is the **active person** at a time. The active lane is framed with **corner brackets on all four corners**. You drive everything from a single set of keys and switch focus with Space.

| Key | Action | Description |
| :--- | :--- | :--- |
| **Spacebar** | Switch active person | Toggles the active lane (Person 1 ⇄ Person 2). Disabled while recording. |
| **Z** | Record (push-to-talk) | Hold to record the **active** person; release to transcribe & translate. |
| **← Left Arrow** | Previous language | Rotates the **active** person's language backward. |
| **→ Right Arrow** | Next language | Rotates the **active** person's language forward. |

Notes:
- The active lane shows four-corner brackets; while it is recording, the brackets invert to black along with the lane's color reversal.
- Best for one-handed / single-operator use.

### Vertical Mode — "two-hand" (original mapping)
Each lane has its **own dedicated keys** — there is no active-person concept and **no bracket highlight**. Both people can be controlled independently.

| Key | Action | Description |
| :--- | :--- | :--- |
| **Z** | Record — Person 1 (push-to-talk) | Hold to record Lane 1; release to transcribe & translate. |
| **X** | Record — Person 2 (push-to-talk) | Hold to record Lane 2; release to transcribe & translate. |
| **← Left Arrow** | Previous language — Person 1 | Rotates Lane 1's language backward. |
| **→ Right Arrow** | Next language — Person 1 | Rotates Lane 1's language forward. |
| **− Minus** (`_`) | Previous language — Person 2 | Rotates Lane 2's language backward. |
| **+ Plus** (`=`) | Next language — Person 2 | Rotates Lane 2's language forward. |

Notes:
- No corner-bracket selection highlight in this mode.
- Best for two operators, each handling their own side.

### Common behavior (both modes)
- **Input focus guard:** all shortcuts are ignored while focus is on a configuration field (`<input>`, `<textarea>`, or `<select>`) — e.g. when editing the API endpoint or settings.
- **Recording lock:** language rotation is blocked while the microphone starts, records, or finishes encoding. Only the finger, mouse button, or key that started a recording can end it.
- **Touch and keyboard:** both modes keep their original shortcuts and also provide on-screen language arrows and hold-to-talk buttons.

### Switching modes
Open **Settings (⚙)** → **Keyboard Mode** → choose **Landscape** or **Vertical**. The change takes effect immediately and persists on the device.

| Setting value | Mode |
| :--- | :--- |
| `landscape` | Active-person scheme (Space / Z / ← →) — default |
| `vertical` | Two-hand scheme (Z / X / ← → / − +) |

## Credits
Made by a small team at [Google Creative Lab](https://github.com/googlecreativelab):
- [Alan Yam](https://github.com/alanvww)
- [Shashwath Santosh](https://x.com/shashwth)
- [Dan Motzenbecker](https://github.com/dmotz)

## Disclaimer

This is not an officially supported Google product. This project is not
eligible for the [Google Open Source Software Vulnerability Rewards
Program](https://bughunters.google.com/open-source-security).
