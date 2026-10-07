# Persian recognition and translation accuracy

There are two separate models in the path: Whisper creates the Persian source
text, then Gemma translates that text. A damaged transcript can change Gemma's
interpretation. If correctly typed Persian also translates incorrectly, that
is a translation-model error. The displayed source text is kept intact so the
two stages can be checked independently.

The reported greeting appears to mean `من خوبم، شما چطور هستین؟` ("I'm fine.
How are you?"). The other screenshot's source contains spelling/word-boundary
errors around `با شما خوشبختم`. The correctly written `از ملاقات با شما خوشبختم`
means "Nice to meet you", without a statement about being tired. Confirm what
was actually spoken before treating a reconstructed sentence as a reference.

## Capture changes

The start cue now sounds after the microphone is ready, rather than at the
initial press while device setup is still pending. Wait for the short cue and
the Recording indicator before speaking. Continue holding until the sentence
ends. The capture callback uses 1024 frames instead of 4096, reducing the
possible unsubmitted final block at 48kHz from about 85ms to about 21ms.

Downsampling now uses a low-pass filter before reducing the sample rate.
Previously, direct interpolation could turn noise above 8kHz into lower
frequencies in the 16kHz audio. Frequency tests check suppression of a 12kHz
tone and retention of a 1kHz speech-band tone at 44.1kHz and 48kHz capture rates.
These corrections improve the capture path; they do not establish recognition
accuracy on a particular microphone or accent. All languages keep the same
16kHz mono Float32 `/api/stt` contract.

Whisper logs include `rms_db`, `peak` and `clipped_pct`. Consistently very low
levels or heavy clipping warrant checking the microphone input gain and
distance. These statistics include pauses; they are not speech-confidence
scores. Recordings are not stored by default.

## Optional stronger Persian recognition

**Accurate** means five-candidate decoding with full context using the selected
model. It does not automatically choose a larger model. The original default
is multilingual `small-q5_1`.

For a stronger model to evaluate, stop the app and run once while online:

```bash
cd ~/Desktop/gemma-translator
git pull --ff-only
./setup-offline-speech.sh --persian-quality
npm --prefix frontend run build
./start.sh --prod
```

This downloads `ggml-large-v3-turbo-q5_0.bin` (574,041,195 bytes, about 547MiB),
checks its pinned SHA-256, and updates only `WHISPER_FA_MODEL` and
`WHISPER_FA_PROFILE` in `speech.env`. Other settings remain intact. Existing
installations need the Python venv/native engines from `./setup.sh`. Fresh
clones should run `./setup.sh` first. Later setup runs also preload the chosen
Persian preset. There are no runtime downloads or cloud inference calls.

For systemd installations, stop `gemma-translator.service` before updating and
start it afterward instead of launching another `start.sh`. Refresh Chromium,
choose **Accurate** under Settings → Persian Speech Recognition, and select
Persian in the source lane. Verify the backend log includes:

```text
[STT] lang=fa engine=whisper.cpp/server profile=accurate model=ggml-large-v3-turbo-q5_0.bin ...
```

Urdu and French keep `WHISPER_MODEL_PATH` and their original decoding settings.
The shared worker keeps just one model loaded: changing between different
Persian and French/Urdu models stops the previous worker and loads the next
model. This avoids retaining both models in RAM, but switching has a load cost.
Moonshine recognition and every TTS voice remain unchanged.

The [upstream Turbo model](https://huggingface.co/openai/whisper-large-v3-turbo)
uses the large-v3 encoder and a reduced decoder. This does not guarantee better
results for every Persian speaker. In a local Windows CPU test, four synthetic
Persian sentences exercised the quantized model successfully. It recovered
more recognizable wording in several cases, but still made errors and took
about 22–25 seconds per clip. Those are **not Raspberry Pi timings** or a
controlled accuracy comparison: synthetic speech differed from earlier small
tests. Full-context inference can be substantially slower on a Pi. Measure
whole-app RAM and latency with Gemma running before keeping this option.

To return to the original model, remove `WHISPER_FA_MODEL` from `speech.env` and
restart. To use an existing local Persian-capable multilingual GGML model,
set `WHISPER_FA_MODEL_PATH` instead. A custom Persian path has priority over
the preset; unset it before running `--persian-quality`. Missing models produce
an explicit setup error rather than silently switching engines or languages.

## Isolate Gemma's translation

The updated compact prompt allows clear speech-to-text spelling errors to be
resolved in sentence context, while keeping ambiguous wording ambiguous.
Previously, it allowed only spacing corrections. It remains below 320
characters for supported directed language pairs, uses one Gemma request, and
keeps `temperature: 0`. There are no hard-coded greeting replacements or
extra correction-model calls. The new wording needs evaluation on the actual
installed Gemma model; unit tests check requests, not linguistic quality.

Keep the app running. In another terminal, test correctly typed Persian:

```bash
npm --prefix frontend run check:persian -- --source fa --target en --text "از ملاقات با شما خوشبختم." --include-compact --repeat 2
```

`previous` is the original short prompt, `compact-spacing` is the preceding
compact prompt, and `current` is the updated UI prompt. Both rounds use the
same text and reverse request order. Review meaning, not exact English wording.
"Nice to meet you" and "I'm pleased to meet you" can both be correct.

The following runs five Persian → English cases, including greetings,
tiredness versus happiness, negation, a time, a name and uncertainty:

```bash
npm --prefix frontend run check:persian -- --source fa --target en
```

Repeat a text-only check with the exact source shown by the UI. If the clean
sentence succeeds but the recorded transcript fails, focus on recognition.
If the clean sentence also fails, changing Whisper or the voice will not fix
that translation; retain the outputs and model name for a Gemma comparison.
