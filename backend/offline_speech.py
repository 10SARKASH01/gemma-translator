# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

"""Local speech adapters. Downloads belong to setup_speech.py, never requests."""

import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
from types import SimpleNamespace
import wave

import numpy as np

MOONSHINE_STT_LANGS = {"en", "ar", "es", "ja", "zh", "ko"}
WHISPER_STT_LANGS = {"fa", "ur"}
MOONSHINE_TTS_LANG_MAP = {
    "ar": "ar-msa", "en": "en-us", "es": "es-es",
    "ja": "ja-jp", "zh": "zh-hans", "ko": "ko-kr",
}
MOONSHINE_TTS_VOICE_MAP = {"zh": "kokoro_zf_xiaoxiao"}
_warned_whisper_cli = False


class OfflineSpeechError(RuntimeError):
    """A local speech dependency is missing, invalid, or failed."""


def speech_dir():
    default = Path(__file__).resolve().parent.parent / "models" / "offline-speech"
    return Path(os.environ.get("OFFLINE_SPEECH_DIR", default)).expanduser().resolve()


def moonshine_tts_dir():
    return speech_dir() / "moonshine" / "tts"


def moonshine_stt_model(language):
    """Read setup's manifest without invoking Moonshine's network downloader."""
    manifest = speech_dir() / "moonshine-stt.json"
    try:
        entry = json.loads(manifest.read_text(encoding="utf-8"))[language]
        model_path = Path(entry["model_path"])
        if not model_path.is_dir() or not any(model_path.iterdir()):
            raise ValueError(f"Missing model directory: {model_path}")
        return str(model_path), int(entry["model_arch"])
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise OfflineSpeechError(
            f"Moonshine STT assets for {language} are unavailable ({exc}). "
            "Run ./setup.sh while online, using the same OFFLINE_SPEECH_DIR at runtime."
        ) from exc


def executable(env_name, default):
    requested = os.environ.get(env_name, str(default))
    resolved = shutil.which(os.path.expanduser(requested))
    if not resolved:
        raise OfflineSpeechError(
            f"Missing executable {requested!r}. Run ./setup.sh or set {env_name} "
            "to an installed executable's path."
        )
    return resolved


def positive_int(env_name, default):
    try:
        value = int(os.environ.get(env_name, default))
        if value > 0:
            return value
    except ValueError:
        pass
    raise OfflineSpeechError(f"{env_name} must be a positive integer.")


def run_speech_command(args, *, input_text=None, timeout=300):
    try:
        return subprocess.run(
            args, input=input_text, capture_output=True, text=True,
            encoding="utf-8", errors="replace", check=True, timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise OfflineSpeechError(
            f"{Path(args[0]).name} exceeded {timeout}s. Try a smaller multilingual "
            "Whisper model or increase SPEECH_TIMEOUT_SECONDS."
        ) from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or str(exc)).strip()[-600:]
        raise OfflineSpeechError(f"{Path(args[0]).name} failed: {detail}") from exc
    except OSError as exc:
        raise OfflineSpeechError(f"Could not run {args[0]}: {exc}") from exc


class WhisperCppRecognizer:
    engine_name = "whisper.cpp"

    def __init__(self, language):
        if language not in WHISPER_STT_LANGS:
            raise ValueError(f"Whisper fallback does not handle {language}")
        self.language = language
        mode = os.environ.get("WHISPER_MODE", "auto").lower()
        if mode not in {"auto", "server", "cli"}:
            raise OfflineSpeechError("WHISPER_MODE must be auto, server or cli.")
        server_default = speech_dir() / "whisper.cpp" / "build" / "bin" / "whisper-server"
        server_requested = os.environ.get("WHISPER_SERVER_BINARY", str(server_default))
        server_binary = shutil.which(os.path.expanduser(server_requested)) if mode != "cli" else None
        if mode == "server" and not server_binary:
            executable("WHISPER_SERVER_BINARY", server_default)
        self.mode = "server" if server_binary else "cli"
        self.binary = server_binary or executable(
            "WHISPER_CPP_BINARY", speech_dir() / "whisper.cpp" / "build" / "bin" / "whisper-cli",
        )
        if mode == "auto" and self.mode == "cli":
            global _warned_whisper_cli
            if not _warned_whisper_cli:
                print(
                    "[STT] whisper-server unavailable; using whisper-cli. Run ./setup.sh "
                    "to keep the multilingual model loaded between requests.", flush=True,
                )
                _warned_whisper_cli = True
        if self.mode == "server":
            self.engine_name = "whisper.cpp/server"
        self.model = Path(os.environ.get(
            "WHISPER_MODEL_PATH", speech_dir() / "whisper" / "ggml-small-q5_1.bin",
        )).expanduser().resolve()
        try:
            with self.model.open("rb") as model_file:
                magic, vocabulary = struct.unpack("<II", model_file.read(8))
            # GGML Whisper header: magic then n_vocab. English-only models have
            # 51864 tokens; multilingual models have at least 51865.
            if magic != 0x67676D6C or vocabulary < 51865:
                raise ValueError("Expected a multilingual GGML Whisper model, not .en or GGUF")
        except (OSError, ValueError, struct.error) as exc:
            raise OfflineSpeechError(
                f"Invalid/missing multilingual Whisper model at {self.model}: {exc}. "
                "Run ./setup.sh or set WHISPER_MODEL_PATH to a multilingual ggml-*.bin model."
            ) from exc
        self.threads = positive_int("WHISPER_THREADS", 4)
        self.timeout = positive_int("SPEECH_TIMEOUT_SECONDS", 300)

    def _server(self):
        from whisper_server import get_whisper_server
        return get_whisper_server(self.binary, self.model, self.threads, self.timeout)

    def warmup(self):
        if self.mode == "server":
            self._server().warmup()

    def transcribe_without_streaming(self, audio, sample_rate):
        if sample_rate != 16000:
            raise ValueError("whisper.cpp input must be 16 kHz mono")
        samples = np.asarray(audio, dtype=np.float32)
        if samples.ndim != 1 or not samples.size or not np.isfinite(samples).all():
            raise ValueError("Audio must contain finite mono Float32 PCM samples")
        print(f"[STT] lang={self.language} engine={self.engine_name}", flush=True)
        if self.mode == "server":
            with io.BytesIO() as buffer:
                with wave.open(buffer, "wb") as wav_file:
                    wav_file.setnchannels(1)
                    wav_file.setsampwidth(2)
                    wav_file.setframerate(16000)
                    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
                    wav_file.writeframes(pcm.tobytes())
                text = self._server().transcribe(buffer.getvalue(), self.language)
            return SimpleNamespace(lines=[SimpleNamespace(text=text)])
        # Conversion is automatic: the browser still sends the existing PCM API
        # payload. Temporary recordings and transcripts are deleted after use.
        with tempfile.TemporaryDirectory(prefix="gemma-whisper-") as temp_dir:
            wav_path = Path(temp_dir) / "input.wav"
            output = Path(temp_dir) / "transcript"
            with wave.open(str(wav_path), "wb") as wav_file:
                wav_file.setnchannels(1)
                wav_file.setsampwidth(2)
                wav_file.setframerate(16000)
                pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
                wav_file.writeframes(pcm.tobytes())
            run_speech_command([
                self.binary, "-m", str(self.model), "-f", str(wav_path),
                "-l", self.language, "-t", str(self.threads), "-ng",
                "-otxt", "-of", str(output), "-np", "-nt",
            ], timeout=self.timeout)
            try:
                text = output.with_suffix(".txt").read_text(encoding="utf-8").strip()
            except OSError as exc:
                raise OfflineSpeechError(
                    "whisper.cpp produced no transcript file. Check its CLI version/model."
                ) from exc
        return SimpleNamespace(lines=[SimpleNamespace(text=text)])


def close_whisper_server(shutdown=False):
    from whisper_server import close_whisper_server as close_worker
    close_worker(shutdown=shutdown)


def read_pcm_wav(wav_bytes):
    """Adapt a local synthesizer's WAV to the existing synthesize() contract."""
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as wav_file:
            if wav_file.getnchannels() != 1 or wav_file.getsampwidth() != 2:
                raise ValueError("Expected mono 16-bit PCM WAV")
            rate = wav_file.getframerate()
            frame_count = wav_file.getnframes()
            frames = wav_file.readframes(frame_count)
        if not frames or len(frames) != frame_count * 2 or rate <= 0:
            raise ValueError("Synthesizer returned empty/invalid audio")
        return np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768, rate
    except (EOFError, OSError, ValueError, wave.Error) as exc:
        raise OfflineSpeechError(f"Invalid local TTS audio: {exc}") from exc


class EspeakNGTextToSpeech:
    engine_name = "espeak-ng"

    def __init__(self, language):
        if language not in {"fa", "ur"}:
            raise ValueError(f"eSpeak fallback does not handle {language}")
        self.language = language
        self.binary = executable("ESPEAK_NG_BINARY", "espeak-ng")
        self.timeout = positive_int("SPEECH_TIMEOUT_SECONDS", 300)
        voices = run_speech_command([self.binary, f"--voices={language}"], timeout=10)
        # Do not trust a language fallback in an older/minimal distribution.
        if not any(len(row.split()) > 1 and row.split()[1] == language
                   for row in voices.stdout.splitlines()):
            raise OfflineSpeechError(
                f"eSpeak NG has no {language} voice. Install Debian's espeak-ng and "
                f"espeak-ng-data packages; verify espeak-ng --voices={language}."
            )

    def synthesize(self, text):
        print(f"[TTS] lang={self.language} engine={self.engine_name}", flush=True)
        with tempfile.TemporaryDirectory(prefix="gemma-espeak-") as temp_dir:
            wav_path = Path(temp_dir) / "speech.wav"
            run_speech_command([
                self.binary, "-v", self.language, "-b", "1", "-w", str(wav_path), "--stdin",
            ], input_text=text, timeout=self.timeout)
            try:
                return read_pcm_wav(wav_path.read_bytes())
            except OSError as exc:
                raise OfflineSpeechError("eSpeak NG produced no WAV file.") from exc


class PersianPiperTextToSpeech:
    engine_name = "piper"

    def __init__(self):
        model = Path(os.environ.get(
            "PIPER_FA_MODEL", speech_dir() / "piper" / "fa_IR-amir-medium.onnx",
        )).expanduser().resolve()
        config = Path(os.environ.get("PIPER_FA_CONFIG", str(model) + ".json")).expanduser()
        try:
            metadata = json.loads(config.read_text(encoding="utf-8"))
            if not model.is_file():
                raise ValueError(f"Missing model {model}")
            if metadata.get("espeak", {}).get("voice") != "fa":
                raise ValueError("Piper model must use the Persian fa phonemizer")
        except (OSError, ValueError, TypeError) as exc:
            raise OfflineSpeechError(
                f"Persian Piper voice unavailable: {exc}. Run ./setup.sh or set "
                "PIPER_FA_MODEL/PIPER_FA_CONFIG. For a lighter Persian voice, set "
                "PERSIAN_TTS_ENGINE=espeak-ng."
            ) from exc
        try:
            from piper import PiperVoice
            self.voice = PiperVoice.load(str(model), config_path=str(config), use_cuda=False)
        except (ImportError, OSError, RuntimeError, ValueError) as exc:
            raise OfflineSpeechError(
                f"Could not load Persian Piper: {exc}. Install backend/requirements.txt "
                "in the server's venv or run ./setup.sh."
            ) from exc

    def synthesize(self, text):
        print("[TTS] lang=fa engine=piper", flush=True)
        with io.BytesIO() as buffer:
            with wave.open(buffer, "wb") as wav_file:
                self.voice.synthesize_wav(text, wav_file)
            return read_pcm_wav(buffer.getvalue())


def new_fallback_tts(language):
    if language == "ur":
        return EspeakNGTextToSpeech("ur")
    if language == "fa":
        engine = os.environ.get("PERSIAN_TTS_ENGINE", "piper")
        if engine == "piper":
            return PersianPiperTextToSpeech()
        if engine == "espeak-ng":
            return EspeakNGTextToSpeech("fa")
        raise OfflineSpeechError("PERSIAN_TTS_ENGINE must be piper or espeak-ng.")
    raise ValueError(f"No fallback TTS for {language}")
