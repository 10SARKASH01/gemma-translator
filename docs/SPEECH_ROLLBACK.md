# Restored speech behavior with touchscreen controls

Speech recognition, recording and translation have been restored to the
French-support release `a196e04`, retaining the touchscreen controls from
`1a78c90`. Production code matches `1a78c90`: all nine languages, offline
voices, RTL text, language arrows, hold-to-talk and keyboard controls remain.

Persian, Urdu and French use the original multilingual `small-q5_1` Whisper
model unless the original `WHISPER_MODEL_PATH` setting is customized. Recording
uses the original 4096-frame callbacks and linear 16kHz resampling. Gemma uses
the original short translation prompt. Later recognition profiles, the Turbo
preset, capture filtering and translation-prompt changes have been rolled back.

The reverted recognizer ignores `WHISPER_FA_MODEL`, `WHISPER_FA_MODEL_PATH` and
`WHISPER_FA_PROFILE`, even if a previous setup saved them in `speech.env` or
systemd exports them. A regression test covers that upgrade-to-rollback case.
Saved browser recognition-mode settings are also unused. Downloaded model
files are left in place; no model download is needed to return to the original
installed small model. The original global `WHISPER_MODEL_PATH` remains supported.

Stop the app, then update and rebuild:

```bash
cd ~/Desktop/gemma-translator
git pull --ff-only
npm --prefix frontend run build
./start.sh --prod
```

For systemd installations, stop/start `gemma-translator.service` instead of
launching another instance. Refresh Chromium so it loads the restored capture
code. Select Persian and use the same touchscreen talk button. This rollback
restores prior behavior; tests do not establish recognition accuracy for a
particular microphone or dialect.
