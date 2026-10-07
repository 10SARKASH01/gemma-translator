# Comparing Persian speech recognition on the Pi

Persian recognition still uses local whisper.cpp with the installed multilingual
model and explicit `fa`. The push-to-talk workflow and browser Float32 PCM API
are unchanged. These profiles introduce no cloud service or speech-language
auto-detection. For capture corrections, the optional stronger Persian-only
model and independent translation checks, see [Persian accuracy](PERSIAN_ACCURACY.md).

## Choose a mode in the touchscreen UI

Open Settings → **Persian Speech Recognition**:

| Mode | Behavior |
| --- | --- |
| Use configured default | Uses `WHISPER_FA_PROFILE` from `speech.env`, or Accurate when unset. |
| Accurate | Existing five-candidate decoding with full audio context using the selected model. |
| Faster — check recognition accuracy | One decoding candidate and shorter encoder context for short recordings, with the same installed model. |
| Compare both — slower, logs both results | Runs both modes on the exact same microphone recording and prints transcripts/timings in the backend terminal. The accurate text is translated and spoken. |

The selection is remembered in this browser. It affects Persian **input** only.
Urdu, French and all Moonshine languages retain their existing recognition
settings. Persian speech output and Gemma translation are unchanged.

Choose Compare both, close Settings, select Persian as the source, and hold the
same talk button you normally use. Say a short sentence, then release. No WAV
file or separate recording command is required. The terminal prints:

```text
[STT] lang=fa engine=whisper.cpp/server profile=accurate audio_s=... beam=5 audio_ctx=0
[STT Compare] {"profile": "accurate", "seconds": ..., "text": "..."}
[STT] lang=fa engine=whisper.cpp/server profile=fast audio_s=... beam=1 audio_ctx=512
[STT Compare] {"profile": "fast", "seconds": ..., "text": "..."}
```

Repeat at least once. Compare order alternates to reveal warmup/order effects.
Server/model startup is moved outside the two comparison clocks; each clock
still includes WAV encoding and the native request. In CLI mode, each profile
also includes starting/loading the CLI. The UI's STT duration in comparison
mode covers **both** runs and any warmup, not the fast profile alone.

Try the greeting `من خوبم، شما چطور هستین؟`, then names, numbers, negations,
quiet speech and a longer sentence. Check each transcript against what you
actually said. Keep Faster only if recognition remains acceptable; choose
Accurate immediately if it drops or changes words. Different spelling alone
may be harmless, but lost negations, names or facts are not.

## What the fast profile changes

The Accurate profile retains beam size/best-of 5 and `audio_ctx=0` (the model's
full context). The fast profile uses beam size/best-of 1. For short recordings,
it computes a smaller audio context covering the **entire** recording plus
2.56 seconds of encoder padding, rounded up in 256-position blocks, with a
minimum of 512 positions (10.24 seconds). It does not trim quiet speech, remove
pauses, crop the recording or cap decoded text length. Clips approaching the
model's full context, long recordings and unknown context headers keep
`audio_ctx=0`. At that point only the decoding-candidate change applies.

The model remains resident and shared across French/Persian/Urdu in server mode
when they select the same model. A Persian-only model override switches the
single worker on demand instead of retaining two models.
Every request explicitly restores beam size, best-of and context, so Persian
fast settings cannot leak into a later Urdu/French/accurate request. CLI fallback
receives equivalent flags. Language detection and Whisper translation stay off.

Reduced context is a speed/accuracy tradeoff, not a proven equivalent to full
context. The [pinned native server](https://github.com/ggml-org/whisper.cpp/blob/v1.8.3/examples/server/server.cpp)
supports per-request `beam_size`, `best_of` and `audio_ctx`; the
[native encoder](https://github.com/ggml-org/whisper.cpp/blob/v1.8.3/src/whisper.cpp)
uses the selected context for its graphs. The actual improvement depends on
the Pi, utterance, model, decoding fallbacks and current CPU/memory load.

## Backend configuration and API

`WHISPER_FA_PROFILE=accurate` is the default. To configure fast Persian input
for clients using the default mode, set `WHISPER_FA_PROFILE=fast` in `speech.env`
and restart. Invalid values produce an actionable configuration error. The UI
can explicitly override the default without restarting. Optionally prewarm your
usual pair using `SPEECH_PREWARM_LANGUAGES=fa,en`; this helps first-use loading,
but it does not reduce repeated recognition inference time.

`POST /api/stt` accepts an optional `whisper_profile` for `language=fa`:
`accurate`, `fast` or `compare`. Existing clients may omit it. Normal responses
remain `{"text":"..."}`. Compare responses retain that text field and add:

```json
{
  "text": "accurate transcript",
  "comparison": [
    {"profile": "accurate", "seconds": 0.0, "text": "accurate transcript"},
    {"profile": "fast", "seconds": 0.0, "text": "fast transcript"}
  ]
}
```

These are schema placeholders, not measured times. Invalid profiles or profile
overrides for other languages return HTTP 400. There is no English fallback.

## Install and validate

Stop the app, then:

```bash
cd ~/Desktop/gemma-translator
git pull --ff-only
npm --prefix frontend run build
./start.sh --prod
```

For systemd, stop/start `gemma-translator.service` instead of launching a second
copy. Refresh Chromium to load the new selector. No setup/model download is
required on an installation already using the pinned whisper.cpp server.

Development tests cover accurate defaults, Persian-only routing, context sizing
without losing PCM samples, long recordings, worker reuse, restoring other
languages, CLI flags, comparison order, original JSON text and invalid requests.
A real v1.8.3 Windows CPU test with the default multilingual `small-q5_1` model
and four synthetic Persian utterances exercised both profiles and returned
transcripts. Fast took less time in that test, but transcripts differed and
both profiles made recognition errors on synthesized speech. It establishes
native integration, not Pi latency or equivalent linguistic accuracy. Real
microphone comparisons on your Pi are required before choosing the fast mode.
