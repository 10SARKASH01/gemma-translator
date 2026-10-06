# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

import base64
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import wave

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import offline_speech as speech
import server
import setup_speech


def write_test_wav(wav_file):
    wav_file.setnchannels(1)
    wav_file.setsampwidth(2)
    wav_file.setframerate(22050)
    wav_file.writeframes(np.array([0, 1000, -1000, 0], dtype="<i2").tobytes())


class OfflineSpeechTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {
            "OFFLINE_SPEECH_DIR": str(self.root),
            "WHISPER_CPP_BINARY": str(self.root / "whisper-cli"),
            "WHISPER_MODEL_PATH": str(self.root / "whisper.bin"),
            "ESPEAK_NG_BINARY": str(self.root / "espeak-ng"),
            "PIPER_FA_MODEL": str(self.root / "fa.onnx"),
            "PIPER_FA_CONFIG": str(self.root / "fa.onnx.json"),
            "PERSIAN_TTS_ENGINE": "piper",
            "WHISPER_THREADS": "4", "SPEECH_TIMEOUT_SECONDS": "30",
        })
        self.env.start()
        (self.root / "whisper.bin").write_bytes(struct.pack("<II", 0x67676D6C, 51865))
        (self.root / "fa.onnx").write_bytes(b"mock local ONNX model")
        (self.root / "fa.onnx.json").write_text(json.dumps({"espeak": {"voice": "fa"}}))
        manifest = {}
        for lang in speech.MOONSHINE_STT_LANGS:
            directory = self.root / lang
            directory.mkdir()
            (directory / "model.ort").write_bytes(b"mock")
            manifest[lang] = {"model_path": str(directory), "model_arch": 0}
        (self.root / "moonshine-stt.json").write_text(json.dumps(manifest))
        self.moon = SimpleNamespace(
            ModelArch=int,
            Transcriber=Mock(return_value=SimpleNamespace(lines=[])),
            TextToSpeech=Mock(),
            get_model_for_language=Mock(side_effect=AssertionError("Runtime must not download")),
        )
        self.moon_patch = patch.dict(sys.modules, {"moonshine_voice": self.moon})
        self.moon_patch.start()
        server._stt_recognizers.clear()
        server._tts_engines.clear()

    def tearDown(self):
        server._stt_recognizers.clear()
        server._tts_engines.clear()
        self.moon_patch.stop()
        self.env.stop()
        self.temp.cleanup()

    def handler(self, path, data=None):
        handler = object.__new__(server.ProxyHTTPRequestHandler)
        body = json.dumps(data).encode() if data is not None else b""
        handler.path = path
        handler.headers = {"Content-Length": str(len(body))}
        handler.rfile = io.BytesIO(body)
        handler.wfile = io.BytesIO()
        handler.send_response = Mock()
        handler.send_header = Mock()
        handler.end_headers = Mock()
        return handler

    def assert_audio_response(self, handler):
        handler.send_response.assert_called_once_with(200)
        handler.send_header.assert_any_call("Content-Type", "audio/wav")
        samples, rate = speech.read_pcm_wav(handler.wfile.getvalue())
        self.assertEqual(rate, 22050)
        self.assertEqual(len(samples), 4)
        self.assertGreater(float(np.abs(samples).max()), 0)

    def test_both_languages_use_whisper_and_existing_pcm_api(self):
        pcm = np.array([0, 0.5, -0.5, 1.5], dtype="<f4")
        for language, expected in [("fa", "سلام دنیا"), ("ur", "آپ کیسے ہیں؟")]:
            with self.subTest(language=language):
                def transcribe(args, **kwargs):
                    self.assertEqual(args[args.index("-l") + 1], language)
                    self.assertNotIn("-tr", args)
                    self.assertNotIn("--translate", args)
                    wav_path = Path(args[args.index("-f") + 1])
                    with wave.open(str(wav_path), "rb") as wav_file:
                        self.assertEqual(wav_file.getframerate(), 16000)
                        self.assertEqual(wav_file.getnchannels(), 1)
                        self.assertEqual(wav_file.getsampwidth(), 2)
                        self.assertEqual(wav_file.getnframes(), 4)
                    output = Path(args[args.index("-of") + 1]).with_suffix(".txt")
                    output.write_text(expected, encoding="utf-8")
                    self.last_temp_path = wav_path.parent
                    return subprocess.CompletedProcess(args, 0, "", "")
                handler = self.handler("/api/stt", {
                    "audio_base64": base64.b64encode(pcm.tobytes()).decode(), "language": language,
                })
                with patch("offline_speech.shutil.which", side_effect=lambda value: value), \
                     patch("offline_speech.subprocess.run", side_effect=transcribe):
                    handler.handle_stt()
                handler.send_response.assert_called_once_with(200)
                self.assertEqual(json.loads(handler.wfile.getvalue()), {"text": expected})
                self.assertIsInstance(server._stt_recognizers[language], speech.WhisperCppRecognizer)
                self.assertFalse(self.last_temp_path.exists())
        self.moon.Transcriber.assert_not_called()
        self.moon.get_model_for_language.assert_not_called()

    def test_every_existing_language_keeps_moonshine_and_lru_limit(self):
        for language in sorted(speech.MOONSHINE_STT_LANGS):
            server.get_stt_recognizer(language)
            self.assertEqual(self.moon.Transcriber.call_args.kwargs["model_path"], str(self.root / language))
            self.assertLessEqual(len(server._stt_recognizers), server.MAX_MODELS)
        self.assertEqual(self.moon.Transcriber.call_count, 6)
        self.moon.get_model_for_language.assert_not_called()

    def test_existing_tts_keeps_voices_but_disables_downloads(self):
        for language, moon_lang in speech.MOONSHINE_TTS_LANG_MAP.items():
            server.get_tts_engine(language)
            args, kwargs = self.moon.TextToSpeech.call_args
            self.assertEqual(args, (moon_lang,))
            self.assertFalse(kwargs["download"])
            self.assertEqual(kwargs["asset_root"], speech.moonshine_tts_dir())
            self.assertEqual(kwargs["voice"], speech.MOONSHINE_TTS_VOICE_MAP.get(language))

    def test_persian_piper_returns_wav_through_existing_api(self):
        def synthesize(text, wav_file):
            self.assertEqual(text, "سلام")
            write_test_wav(wav_file)
        fake_voice = SimpleNamespace(synthesize_wav=synthesize)
        piper = SimpleNamespace(PiperVoice=SimpleNamespace(load=Mock(return_value=fake_voice)))
        with patch.dict(sys.modules, {"piper": piper}):
            handler = self.handler("/api/tts?text=%D8%B3%D9%84%D8%A7%D9%85&lang=fa")
            handler.handle_tts()
        self.assert_audio_response(handler)
        piper.PiperVoice.load.assert_called_once_with(
            str(self.root / "fa.onnx"), config_path=str(self.root / "fa.onnx.json"), use_cuda=False,
        )
        self.moon.TextToSpeech.assert_not_called()

    def test_urdu_espeak_and_explicit_persian_espeak_return_wav(self):
        for language in ["ur", "fa"]:
            with self.subTest(language=language), patch.dict(os.environ, {"PERSIAN_TTS_ENGINE": "espeak-ng"}):
                def synthesize(args, **kwargs):
                    if f"--voices={language}" in args:
                        return subprocess.CompletedProcess(args, 0, f"Pty Language Age/Gender VoiceName File\n 5 {language} --/M NativeVoice example\n", "")
                    self.assertEqual(args[args.index("-v") + 1], language)
                    self.assertEqual(args[args.index("-b") + 1], "1")
                    self.assertEqual(kwargs["input"], "سلام")
                    self.assertEqual(kwargs["encoding"], "utf-8")
                    with wave.open(args[args.index("-w") + 1], "wb") as wav_file:
                        write_test_wav(wav_file)
                    return subprocess.CompletedProcess(args, 0, "", "")
                with patch("offline_speech.shutil.which", side_effect=lambda value: value), \
                     patch("offline_speech.subprocess.run", side_effect=synthesize):
                    handler = self.handler(f"/api/tts?text=%D8%B3%D9%84%D8%A7%D9%85&lang={language}")
                    handler.handle_tts()
                self.assert_audio_response(handler)
        self.moon.TextToSpeech.assert_not_called()

    def test_missing_whisper_binary_is_actionable_503(self):
        with patch("offline_speech.shutil.which", return_value=None):
            handler = self.handler("/api/stt", {"audio_base64": "AAAAAA==", "language": "fa"})
            handler.handle_stt()
        handler.send_response.assert_called_once_with(503)
        self.assertIn("WHISPER_CPP_BINARY", json.loads(handler.wfile.getvalue())["error"])
        self.moon.Transcriber.assert_not_called()

    def test_missing_and_english_only_whisper_model_are_rejected(self):
        with patch("offline_speech.shutil.which", side_effect=lambda value: value):
            (self.root / "whisper.bin").unlink()
            with self.assertRaisesRegex(speech.OfflineSpeechError, "WHISPER_MODEL_PATH"):
                speech.WhisperCppRecognizer("ur")
            (self.root / "whisper.bin").write_bytes(struct.pack("<II", 0x67676D6C, 51864))
            with self.assertRaisesRegex(speech.OfflineSpeechError, "multilingual"):
                speech.WhisperCppRecognizer("fa")

    def test_missing_persian_model_does_not_fall_back_to_english(self):
        (self.root / "fa.onnx").unlink()
        handler = self.handler("/api/tts?text=hello&lang=fa")
        handler.handle_tts()
        handler.send_response.assert_called_once_with(503)
        self.assertIn("PIPER_FA_MODEL", json.loads(handler.wfile.getvalue())["error"])
        self.moon.TextToSpeech.assert_not_called()

    def test_wrong_language_piper_model_is_rejected(self):
        (self.root / "fa.onnx.json").write_text(json.dumps({"espeak": {"voice": "en-us"}}))
        with self.assertRaisesRegex(speech.OfflineSpeechError, "Persian fa phonemizer"):
            speech.PersianPiperTextToSpeech()

    def test_missing_urdu_binary_and_voice_are_actionable(self):
        with patch("offline_speech.shutil.which", return_value=None):
            with self.assertRaisesRegex(speech.OfflineSpeechError, "ESPEAK_NG_BINARY"):
                speech.EspeakNGTextToSpeech("ur")
        with patch("offline_speech.shutil.which", side_effect=lambda value: value), \
             patch("offline_speech.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "5 en --/M English", "")):
            with self.assertRaisesRegex(speech.OfflineSpeechError, "no ur voice"):
                speech.EspeakNGTextToSpeech("ur")

    def test_missing_moonshine_manifest_is_offline_error(self):
        (self.root / "moonshine-stt.json").unlink()
        with self.assertRaisesRegex(speech.OfflineSpeechError, "setup.sh"):
            server.get_stt_recognizer("en")
        self.moon.get_model_for_language.assert_not_called()

    def test_setup_preloads_all_existing_languages_and_writes_usable_manifest(self):
        self.moon.get_model_for_language = Mock(side_effect=lambda lang, **kwargs: (
            str(self.root / lang), SimpleNamespace(value=0),
        ))
        self.moon.download_tts_assets = Mock()
        self.moon.TextToSpeech.return_value = SimpleNamespace(close=Mock())
        fallback = SimpleNamespace(
            engine_name="local-test", synthesize=Mock(return_value=(np.ones(100), 22050)),
        )
        with patch("setup_speech.WhisperCppRecognizer", return_value=SimpleNamespace(binary="whisper-cli")), \
             patch("setup_speech.run_speech_command"), \
             patch("setup_speech.new_fallback_tts", return_value=fallback) as get_voice:
            setup_speech.setup_speech()
        self.assertEqual(get_voice.call_args_list[0].args, ("fa",))
        self.assertEqual(get_voice.call_args_list[1].args, ("ur",))
        self.assertEqual(self.moon.get_model_for_language.call_count, 6)
        self.assertEqual(self.moon.download_tts_assets.call_count, 6)
        self.assertEqual(set(json.loads((self.root / "moonshine-stt.json").read_text())), speech.MOONSHINE_STT_LANGS)
        for language in speech.MOONSHINE_STT_LANGS:
            self.assertEqual(speech.moonshine_stt_model(language), (str(self.root / language), 0))
        for call in self.moon.TextToSpeech.call_args_list:
            self.assertFalse(call.kwargs["download"])

    def test_setup_keeps_default_destination_names_when_previous_links_are_broken(self):
        whisper_path = self.root / "whisper" / "ggml-small-q5_1.bin"
        piper_path = self.root / "piper" / "fa_IR-amir-medium.onnx"
        config_path = self.root / "piper" / "fa_IR-amir-medium.onnx.json"
        broken_paths = {whisper_path, piper_path, config_path}
        original_resolve = Path.resolve

        def resolve_without_following_broken_destinations(path, *args, **kwargs):
            if path in broken_paths:
                raise AssertionError("Setup must replace a broken destination, not resolve its target")
            return original_resolve(path, *args, **kwargs)

        self.moon.get_model_for_language = Mock(side_effect=lambda lang, **kwargs: (
            str(self.root / lang), SimpleNamespace(value=0),
        ))
        self.moon.download_tts_assets = Mock()
        self.moon.TextToSpeech.return_value = SimpleNamespace(close=Mock())
        fallback = SimpleNamespace(
            engine_name="local-test", synthesize=Mock(return_value=(np.ones(100), 22050)),
        )
        with patch.dict(os.environ, {"OFFLINE_SPEECH_DIR": str(self.root)}, clear=True), \
             patch("pathlib.Path.resolve", new=resolve_without_following_broken_destinations), \
             patch("setup_speech.download_model_file") as download, \
             patch("setup_speech.WhisperCppRecognizer", return_value=SimpleNamespace(binary="whisper-cli")), \
             patch("setup_speech.run_speech_command"), \
             patch("setup_speech.new_fallback_tts", return_value=fallback):
            setup_speech.setup_speech()
        self.assertEqual([call.args[2] for call in download.call_args_list], [
            whisper_path, piper_path, config_path, piper_path.parent / "MODEL_CARD",
        ])
        self.assertEqual(download.call_args_list[1].args[1], "fa/fa_IR/amir/medium/fa_IR-amir-medium.onnx")

    def test_invalid_audio_is_400(self):
        for audio in [np.array([float("nan")], dtype="<f4").tobytes(), b"bad"]:
            handler = self.handler("/api/stt", {"audio_base64": base64.b64encode(audio).decode(), "language": "fa"})
            handler.handle_stt()
            handler.send_response.assert_called_once_with(400)

    def test_local_command_failure_timeout_and_invalid_audio_have_errors(self):
        for error in [subprocess.TimeoutExpired(["whisper-cli"], 1),
                      subprocess.CalledProcessError(1, ["whisper-cli"], stderr="bad model")]:
            with patch("offline_speech.subprocess.run", side_effect=error):
                with self.assertRaises(speech.OfflineSpeechError):
                    speech.run_speech_command(["whisper-cli"])
        with self.assertRaisesRegex(speech.OfflineSpeechError, "Invalid local TTS audio"):
            speech.read_pcm_wav(b"not a WAV")


@unittest.skipUnless(os.environ.get("RUN_OFFLINE_SPEECH_SMOKE") == "1", "Enable on a configured Pi for real voices")
class RealSpeechSmokeTests(unittest.TestCase):
    def test_local_persian_and_urdu_voices(self):
        for lang, text in [("fa", "سلام، حال شما چطور است؟"), ("ur", "آپ کیسے ہیں؟")]:
            with self.subTest(language=lang):
                samples, rate = speech.new_fallback_tts(lang).synthesize(text)
                self.assertGreater(len(samples), 100)
                self.assertGreater(rate, 0)
                self.assertTrue(np.isfinite(samples).all())
                self.assertGreater(float(np.abs(samples).max()), 0)


if __name__ == "__main__":
    unittest.main()
