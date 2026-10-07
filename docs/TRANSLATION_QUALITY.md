# Checking Persian translation quality

The UI displays the STT transcript before translation. If the transcript is
correct but the Persian text has the wrong meaning, investigate Gemma's
translation stage. Speech output reads the translated text; changing Whisper
or the Persian voice will not repair a mistranslated sentence.

The application sends the complete transcript in one user message. It does not
split text into individual words for translation. TTS chunking happens after
the translated text is available and does not alter the displayed translation.

The original short prompt asked only to translate and preserve meaning/names.
The current compact prompt asks for natural translation by meaning and idioms,
not word by word, preserving facts, tone, names, numbers, negation and uncertainty.
For a Persian target, it requests Iranian Persian (Farsi) in Persian script.
All source languages share this Persian guidance.

For Persian input, it also asks Gemma to read colloquial Persian and resolve
clear speech-to-text typos from context, keeping ambiguous wording ambiguous.
This replaces the preceding restriction to spacing errors only. The displayed
STT transcript remains unchanged. This is not a repair
for missing words or a guarantee that a damaged transcript can be understood.

For example, the reported transcript `من خوب هم بشما چه تور هستین` appears to
mean `من خوبم، شما چطور هستین؟` ("I'm fine. How are you?"). The word splitting
in the transcript is imperfect, and the previous output "I am good, what are
you?" also interprets the greeting literally. This case involves Persian input
and English output, so both recognition and translation need to be considered.

Translation still uses one local Gemma request, the same JSON response, and
`temperature: 0`. The extra prompt text has some input-processing cost; it does
not add another generation pass or a model download. A prompt improvement does
not guarantee correct translations from a small general-purpose model.

## Measured prompt latency and the compact revision

On the user's Pi, a text-only check of `من خوبم شما چه تور؟` returned:

| Prompt | English result | Seconds |
| --- | --- | --- |
| Original short prompt | I am fine, and you what? | 3.51 |
| Verbose quality prompt from `873e968` | I am fine, and you? | 14.32 |

The verbose prompt improved this translation but made this request about four
times slower. Earlier UI logs also showed Persian-source translation requests
around 14–15 seconds. This motivated shortening the prompt while retaining
natural meaning, idioms, faithful details and colloquial Persian guidance.
The compact revision stays below 320 characters for every supported directed
language pair. Character length is a budget guard, not a tokenizer measurement
or a prediction of model latency. Its actual quality and timing need another
Pi comparison; they are not established by unit tests.

Whisper recognition is a separate cost. In the supplied log it still took about
13 seconds on a repeated Persian utterance after the server was loaded. Changing
the Gemma prompt does not speed up that recognition stage.

## Compare prompts on the Raspberry Pi

Start the app with its installed model, then open another terminal:

```bash
cd ~/Desktop/gemma-translator
npm --prefix frontend run check:persian
```

This runs five English examples through the old and current prompts using only
`http://127.0.0.1:9379/v1`. It bypasses the microphone and voices so recognition
and pronunciation do not affect the comparison. It uses the same frontend
request builder and response parser as the UI. Each case prints both Persian
translations and timings, prompt character counts, available total token usage,
a sample reference, and what to review. There are ten
sequential inferences, so allow time on the Pi. The first result may include a
cold-start delay. Compare warmed repetitions before drawing latency conclusions.

To reproduce an actual problematic transcript instead:

```bash
npm --prefix frontend run check:persian -- --source en --text "Could you give me a hand?"
```

Use `--source fr`, `ar`, `ur`, etc. with `--text` for another source language.
To compare all three prompt versions on the measured greeting, with alternating
order across two rounds:

```bash
npm --prefix frontend run check:persian -- --source fa --target en --text "من خوبم شما چه تور؟" --include-long --repeat 2
```

`previous` is the original short prompt, `long-quality` reproduces the verbose
prompt from `873e968`, and `current` is the compact prompt used by the updated UI.
Use `--include-compact` to also compare the preceding compact prompt from
`0a4f235` (`compact-spacing`), before the spelling-error guidance changed.
`--include-long` is optional; it is never used in normal app requests. Each round
prints separately. The second reverses request order to help reveal warmup/order
effects. `--repeat` accepts 1–10 rounds; this command makes six sequential
requests. Omit `--include-long` to compare just the original and compact prompts.

For the reported Persian → English example:

```bash
npm --prefix frontend run check:persian -- --source fa --target en --text "من خوب هم بشما چه تور هستین"
npm --prefix frontend run check:persian -- --source fa --target en --text "من خوبم، شما چطور هستین؟"
```

Compare the actual transcript with the corrected text. If the corrected text
works but the transcript fails, recognition errors contribute. If both produce
the same incorrect English meaning, the translation model/prompt is responsible.
These checks inspect Gemma directly; they do not measure microphone recognition.

Use `--model` if Settings uses a different imported model name. `--endpoint`
accepts a different local HTTP loopback port; this diagnostic rejects remote
endpoints. No additional dependency or cloud service is needed (Node 18+).

Review meaning and fluency rather than exact spelling against one reference:
"give me a hand" should request help, "under the weather" should mean feeling
unwell, and "running late" should mean being late. Check that negations,
numbers, uncertainty, names and questions survive. Several Persian phrasings
can be equally correct. A fluent sentence with changed facts is still wrong.

The frontend unit tests verify complete-utterance requests, all language routes,
the translation instructions, and unchanged Persian response text. Their mocked
outputs do **not** measure the installed model's linguistic quality. The manual
check above and real UI examples are required for that conclusion.

## Apply the frontend update

After this change is pushed, stop the running app, pull the update, rebuild, and
restart using your usual development or systemd workflow:

```bash
git pull --ff-only
npm --prefix frontend run build
./start.sh --prod
```

For systemd, stop/start `gemma-translator.service` instead of starting a second
copy manually. Refresh Chromium to load the rebuilt prompt. No speech setup or
model download is required. Development mode uses the updated source directly.

If the translation is still wrong, retain the exact transcript, source language,
Persian output, intended meaning, and model name. Ambiguous speech may need
context that was never stated; this update does not retain conversation history
or guess missing details. Sampling/model changes should be evaluated with the
same sentences and the Pi's memory/latency constraints before being enabled.
