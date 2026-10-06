# Gemma Translator for Android

This is a standalone native Android app for a Samsung Galaxy S24 or another ARM64 phone with at least 8 GB RAM. It runs microphone capture, speech recognition, Gemma translation, and neural speech output **on the phone**. It needs neither a Raspberry Pi nor an inference server. Internet access is used only when you explicitly install models during initial setup.

The Raspberry Pi application remains a separate, unchanged application. This directory has its own Gradle build; the Pi's `setup.sh`, Python backend, and web development server are not needed on Android.

## Install and use the APK

1. Copy `gemma-translator-s24-debug.apk` (or the locally signed release build) onto your phone. Open it in Samsung My Files, allow that app to install unknown apps when prompted, and install **Gemma Translator**.
2. Open the app and tap **Download offline models**. Use Wi-Fi and keep at least **4.5 GB of storage free** before setup. The downloads total approximately **3.6 GB**. Setup has a foreground notification, SHA-256 verification, safe extraction, and Pause/Resume. Interrupted downloads resume when the host supports HTTP Range.
3. Wait for **Ready — all nine languages work offline**. Choose the two people's languages by tapping their language names or the left/right arrows.
4. Hold either person's **Hold to talk** button. Allow microphone permission the first time, then hold again. Wait for **Release to translate**, speak, and release. The selected person's speech is transcribed and translated into the other person's language, then spoken automatically. Each recording is limited to 60 seconds.
5. Try English → Persian, Persian → English, Urdu → French, and French → Urdu. Turn on airplane mode after setup and repeat: recognition, translation, and voices must still work.

The settings menu can disable automatic speech and show model locations and license notices. **Stop / cancel** cancels the current turn; holding either talk button starts a new turn. Leaving the app cancels recording, translation, and playback. The interface supports portrait/landscape, touch, accessibility start/stop activation, and correctly directed Arabic, Persian, and Urdu text.

Models live in the app's private storage, are excluded from Android backups, and survive normal APK updates. Clearing app data or uninstalling removes them. Recordings exist only in memory. The first recognition, translation, or voice request takes longer while native engines initialize; later turns retain the recognition and Gemma engines and one current voice. Initial GPU compilation can be slow, and the app falls back to local CPU inference if the phone's GPU driver rejects the model.

## Engines and resource choices

| Stage | Implementation |
| --- | --- |
| Capture | Android `AudioRecord`, 16 kHz mono PCM, held in memory |
| Recognition, all nine languages | Resident multilingual Whisper small INT8 through Sherpa-ONNX; selected source language is forced |
| Translation, all language pairs | Gemma 4 E2B `.litertlm`, LiteRT-LM 0.13.1, GPU with CPU fallback; text-only, thinking disabled, fresh conversation per turn |
| Arabic, English, Spanish, Japanese, Korean, French voice | Supertonic 3 INT8, explicit selected language |
| Chinese voice | Kokoro INT8, Xiaoxiao |
| Persian voice | Piper Amir neural voice, Persian phonemes |
| Urdu voice | Piper Fasih neural voice, Urdu phonemes |
| Playback | Android `AudioTrack` with transient audio focus |

All speech output uses neural models. Urdu output differs from the Pi's direct eSpeak voice. The neural Persian and Urdu engines use eSpeak NG as a **phonemizer** before neural synthesis. Voice quality still varies with the model and language; this is not a promise of studio quality.

The app serializes inference to avoid simultaneously loading duplicate models. It keeps one resident recognizer and Gemma engine, releases a voice when switching to another voice family, and limits Gemma's context to 4,096 tokens. Translation speech starts with a short chunk; the next chunk can be prepared while the current one plays. Speech recognition windows cover the complete recording rather than silently truncating audio at Whisper's native window limit. No cloud fallback is implemented.

Pinned models, checksums, routing, and voice license references are documented in [SPEECH_MODELS.md](SPEECH_MODELS.md). Gemma's pinned revision, size, and checksum are in `ModelCatalog` in `ModelStore.kt`; the official converted Gemma 4 E2B model is Apache 2.0 licensed.

## Build from a fresh clone

Install **JDK 21** and Android SDK **platforms;android-35**, **build-tools;35.0.0**, and **platform-tools**. Android Studio can install these. Set `JAVA_HOME` and `ANDROID_HOME` (or `ANDROID_SDK_ROOT`); alternatively put the SDK location in the ignored `android/local.properties` as `sdk.dir=...`. Use escaped backslashes/drive colon on Windows, or forward slashes with an escaped drive colon.

JDK 21 is required for the host unit tests because LiteRT-LM 0.13.1 contains Java 21 classes. Android application compilation targets Java 17 and the minimum Android version is 9/API 28. Native libraries are packaged only for ARM64. Gradle, Android/Kotlin plugins, LiteRT-LM, and Sherpa-ONNX are pinned. Gradle downloads the official Sherpa AAR automatically and verifies its SHA-256; no CMake/NDK or speech executable is required to build this app.

From Windows PowerShell:

```powershell
cd android
./build-apk.ps1
```

From Linux/macOS:

```sh
cd android
bash ./build-apk.sh
```

These commands run the JVM tests and Android lint, assemble the APK, and copy it to `android/dist/gemma-translator-s24-debug.apk`. For the release variant use `./build-apk.ps1 -Variant release` or `bash ./build-apk.sh release`.

The release variant currently uses the local debug signing key for sideloading. Supply your own signing configuration before store publication. Keep the same signing key for subsequent updates; a differently signed APK cannot update an installed copy without uninstalling it and removing its models.

Full validation:

```powershell
./gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On Linux/macOS replace `./gradlew.bat` with `bash ./gradlew`.

With USB debugging enabled and Android platform-tools on your path:

```sh
adb install -r dist/gemma-translator-s24-debug.apk
adb logcat -s OfflineTranslator GemmaTranslator
```

Logs report `[STT] lang=...`, `[TTS] lang=...`, Gemma GPU/CPU selection, and stage timings. Audio and transcript contents are not deliberately logged. Native libraries may emit their own diagnostic output.

## Code structure

| File/class | Responsibility and communication |
| --- | --- |
| `MainActivity.kt` | Native two-person UI; owns capture/inference/playback, snapshots both languages per recording, posts results to the UI with generation guards, cancels work on lifecycle changes |
| `SupportedLanguages.kt` | Shared nine-language catalog, RTL classification, selector rotation that skips the other person's language |
| `ModelDownloadService.kt` | Explicit foreground setup; owns installation worker, notification, pause/cancel, wake lock, and app-scoped status broadcasts |
| `ModelStore.kt` | Pinned Gemma/catalog, resumable downloads, SHA verification, bounded safe tar extraction, atomic installs, readiness markers |
| `SpeechAssets.kt` | Pinned Whisper and neural voice archives, required files, voice routing |
| `OfflineSpeech.kt` | Sherpa-ONNX adapter; forced-language recognition, long-audio windows, cached current neural voice, actionable missing-model errors |
| `GemmaTranslator.kt` | Retained LiteRT-LM engine, GPU/CPU fallback, strict translation JSON, fresh conversations, native cancellation |
| `MicrophoneRecorder.kt` | Asynchronous bounded PCM capture; no WAV files and no UI-thread joins |
| `PcmAudioPlayer.kt` | Validates PCM, obtains audio focus, streams audio, stops/releases hardware safely, completes futures after audible playback |
| `SpeechChunks` | Splits translated text into bounded chunks without separating UTF-16 surrogate pairs |
| `app/src/test/` | Host tests for routing, model installation/recovery/errors, Gemma lifecycle/config/JSON, audio cancellation/resource release, languages, and speech chunks |

The only inference path is `MainActivity → MicrophoneRecorder → OfflineSpeech.transcribe → GemmaTranslator.translate → OfflineSpeech.synthesize → PcmAudioPlayer`. The setup path is separate: `MainActivity → ModelDownloadService → ModelStore`.

## Validation and practical limits

The APK was assembled and its signature, ARM64 packaging, and 16 KB native alignment verified. JVM tests and Android lint check the implementation. The exact speech models were also exercised through native Sherpa-ONNX 1.13.8 on Windows CPU: all nine voices produced valid nonempty audio and Whisper returned the selected language codes. Short synthetic Persian, Urdu, and Korean samples had word errors.

There was no attached S24 during development. Real microphone recognition accuracy, GPU compatibility, latency, heat, and pronunciation must be checked on the phone. This app intentionally uses a single multilingual Whisper model rather than the Pi's per-language Moonshine/whisper.cpp mix, so recognition accuracy can differ. Long recordings use consecutive recognition windows and can lose context across a boundary; short conversational turns work best.

The Android distribution includes native GPL components and is distributed under GPLv3-or-later, with third-party components retaining their own licenses. The Pi application retains its existing Apache 2.0 license. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the packaged license/source information before redistributing an APK.
