# Offline pipeline performance

The translator still runs microphone → local STT → local Gemma → local TTS. These changes remove repeated loading and avoid synthesis gaps; they preserve the translation model, Whisper decoding settings, and voices.

## What changed

| Stage | Optimization | What improves |
| --- | --- | --- |
| Persian/Urdu STT | One backend-managed `whisper-server` shares the multilingual model between `fa` and `ur`. WAV conversion happens automatically in memory. | Repeated utterances avoid launching Whisper and loading the model again. |
| Existing STT and all TTS | A configurable pair of speech languages is preloaded; existing two-entry caches remain. | Initial requests for that pair avoid engine construction. |
| Gemma | Shorter language-specific JSON prompt and supported deterministic sampling (`temperature: 0`). | Less prompt processing and consistent output. Speed/translation quality still need device measurement. |
| Gemma startup | One small local completion before the API starts. | Moves first-inference model loading into startup; later requests use LiteRT-LM's existing resident engine. |
| Speech playback | Shorter first phrase, then one following WAV prepared during playback. | Earlier first speech and fewer gaps between chunks. At most one future chunk is prefetched. |
| Diagnostics | Separate STT/Gemma/speech-start UI durations; backend queue/load/inference/encode timings. | Identifies the remaining bottleneck on the Pi. |

Speech playback still waits for the complete Gemma translation. Prefetch overlaps TTS synthesis with audio playback. Actual inference already running on the backend may finish after browser playback is cancelled.

The persistent worker uses the same multilingual GGML model, explicit `fa`/`ur` fields, and disables translation and language detection. Beam size and best-of remain 5, matching the previous CLI settings. Original languages continue to use Moonshine. Runtime requests remain local; downloads still belong to setup.

The [pinned whisper.cpp server](https://github.com/ggml-org/whisper.cpp/blob/v1.8.3/examples/server/server.cpp) supports a shared context and per-request language. The [pinned LiteRT-LM handler](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.13.1/python/litert_lm_cli/commands/openai_handler.py) accepts temperature; it does not implement OpenAI-style completion limits or reasoning-effort controls, so this change does not send those settings.

## Install the update on the Pi

Stop the manually launched app with Ctrl+C. For systemd, run `sudo systemctl stop gemma-translator.service` first. Then, while online:

```bash
cd ~/Desktop/gemma-translator
git pull --ff-only
./setup.sh
npm --prefix frontend ci
npm --prefix frontend run build
```

Setup builds both `whisper-cli` and `whisper-server`, including when the CLI is already installed. Existing model downloads are reused. Choose settings in `speech.env` before starting; create it from `speech.env.example` if you have not configured it yet:

```text
WHISPER_MODE=auto
SPEECH_PREWARM_LANGUAGES=fa,en
GEMMA_WARMUP=1
GEMMA_MODEL_NAME=gemma4-e2b
```

Use your usual pair for `SPEECH_PREWARM_LANGUAGES`: `fa,en`, `ur,en`, or `fa,ur`, for example. Keep at most two codes. The default is `ar,en`, matching initial UI lanes. An empty value disables speech prewarming. `GEMMA_MODEL_NAME` must match the complete model name used in Settings, including model suffixes. `GEMMA_WARMUP=0` skips the startup completion; the first real translation then loads Gemma normally.

Start manually with `./start.sh --prod`, or resume the service with `sudo systemctl start gemma-translator.service`. Production uses the rebuilt frontend. Startup waits for previous listeners to release their ports after graceful termination, and the backend terminates its Whisper child on shutdown.

## Memory and compatibility

`WHISPER_MODE=auto` uses the server if installed and logs a CLI fallback when missing. `WHISPER_MODE=server` requires the server and reports a useful dependency error. `WHISPER_MODE=cli` restores per-utterance processes, releasing model memory after each recording.

`WHISPER_SERVER_BINARY` defaults to `<speech dir>/whisper.cpp/build/bin/whisper-server`; another local executable can be configured. The worker binds to loopback on an automatically chosen port. One model process serves both languages. It remains loaded until shutdown and survives recognizer cache eviction. Persistent residency trades baseline RAM for faster repeated requests. Measure whole-app RAM alongside Gemma and selected voices on the Pi.

The first use can still be slower if it was not prewarmed. Switching among more than two speech languages can evict a Moonshine/Piper engine. A smaller multilingual Whisper model is an optional accuracy/latency tradeoff, not enabled here. A shorter prompt or deterministic sampling does not guarantee identical wording; verify meaning, names, and numbers with real sentences.

## Measure before and after

Use short and long utterances for English → Persian, Persian → English, English → Urdu, Urdu → English, and Persian ↔ Urdu. Record the first result after restart separately from at least five repeated results. Check meaning and pronunciation alongside timing.

The response drawer now displays:

```text
STT: ...s | Gemma: ...s | Speech ready: ...s
```

These are browser elapsed times for each stage. Speech ready measures from the TTS request until first audio begins, not the duration of the entire spoken translation. Token counts appear only when the model response supplies usage.

Backend logs and `Server-Timing` headers expose local work:

```text
[Perf] stage=stt lang=fa prepare_ms=... queue_ms=... load_ms=... inference_ms=... encode_ms=... total_ms=...
[Perf] stage=translation total_ms=...
[Perf] stage=tts lang=fa prepare_ms=... queue_ms=... load_ms=... inference_ms=... encode_ms=... total_ms=...
```

A first Whisper request can include server startup inside inference time; prewarming moves it earlier. Client times include network/response receipt; server times cover local handling. Compare runs with the same hardware/model, utterance length, language pair, and CPU temperature. Check the task monitor or `free -h` for memory and swapping.

After setup, disconnect networking, restart, and repeat. Native recognition accuracy, Pi installation, latency, and peak RAM still require device validation. Development tests verify routing, worker reuse, lifecycle/errors, warmup, playback overlap/cancellation, and API compatibility.

An integration check with the official v1.8.3 Windows server and a multilingual tiny test model confirmed that real `fa`/`ur` requests shared one process and cleanup stopped it. This checks the native protocol, not recognition quality or Raspberry Pi performance; the installed default remains quantized small.
