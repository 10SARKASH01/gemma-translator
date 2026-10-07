"""Persian-only model selection and durable, verified setup without downloads."""

import hashlib
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import offline_speech as speech
import setup_speech


class PersianQualityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.model = self.root / "custom.bin"
        self.model.write_bytes(struct.pack("<III", 0x67676D6C, 51865, 1500))
        self.environment = patch.dict(os.environ, {
            "OFFLINE_SPEECH_DIR": str(self.root), "WHISPER_MODE": "cli",
            "WHISPER_MODEL_PATH": str(self.model), "WHISPER_THREADS": "4",
        }, clear=True)
        self.environment.start()
        self.executable = patch("offline_speech.shutil.which", side_effect=lambda value: value)
        self.executable.start()

    def tearDown(self):
        self.executable.stop()
        self.environment.stop()
        self.temporary.cleanup()

    def test_no_override_preserves_the_existing_shared_model(self):
        for language in ["fa", "ur", "fr"]:
            self.assertEqual(speech.WhisperCppRecognizer(language).model, self.model)

    def test_persian_preset_does_not_change_french_or_urdu_models(self):
        preset = self.root / "whisper" / "ggml-large-v3-turbo-q5_0.bin"
        preset.parent.mkdir()
        preset.write_bytes(self.model.read_bytes())
        with patch.dict(os.environ, {"WHISPER_FA_MODEL": "large-v3-turbo-q5_0"}):
            self.assertEqual(speech.WhisperCppRecognizer("fa").model, preset)
            for language in ["fr", "ur"]:
                self.assertEqual(speech.WhisperCppRecognizer(language).model, self.model)
        # Missing quality assets produce a setup error, never an implicit model switch.
        preset.unlink()
        with patch.dict(os.environ, {"WHISPER_FA_MODEL": "large-v3-turbo-q5_0"}):
            with self.assertRaisesRegex(speech.OfflineSpeechError, "--persian-quality"):
                speech.WhisperCppRecognizer("fa")

    def test_persian_custom_path_has_priority_and_reports_missing_assets(self):
        with patch.dict(os.environ, {"WHISPER_FA_MODEL": "large-v3-turbo-q5_0", "WHISPER_FA_MODEL_PATH": str(self.model)}):
            self.assertEqual(speech.WhisperCppRecognizer("fa").model, self.model)
            self.model.unlink()
            with self.assertRaisesRegex(speech.OfflineSpeechError, "WHISPER_FA_MODEL_PATH"):
                speech.WhisperCppRecognizer("fa")

    def test_bad_persian_preset_is_actionable_and_does_not_affect_other_languages(self):
        with patch.dict(os.environ, {"WHISPER_FA_MODEL": "../other-model"}):
            with self.assertRaisesRegex(speech.OfflineSpeechError, "WHISPER_FA_MODEL must be"):
                speech.WhisperCppRecognizer("fa")
            self.assertEqual(speech.WhisperCppRecognizer("fr").model, self.model)

    def test_quality_setup_preserves_other_settings_and_is_idempotent(self):
        config = self.root / "speech.env"
        config.write_text("# Personal settings\nWHISPER_THREADS=3\nWHISPER_MODEL_PATH=/opt/urdu-french.bin\nexport WHISPER_FA_MODEL =small-q5_1\nWHISPER_FA_PROFILE=fast\n", encoding="utf-8")
        with patch("setup_speech.download_persian_quality_model", return_value=self.model), \
             patch("setup_speech.WhisperCppRecognizer") as recognize:
            setup_speech.enable_persian_quality(config)
            recognize.assert_called_once_with("fa")
            first = config.read_text(encoding="utf-8")
            setup_speech.enable_persian_quality(config)
        self.assertEqual(config.read_text(encoding="utf-8"), first)
        self.assertEqual(first.count("WHISPER_FA_MODEL="), 1)
        self.assertIn("WHISPER_FA_MODEL=large-v3-turbo-q5_0\n", first)
        self.assertIn("WHISPER_FA_PROFILE=accurate\n", first)
        self.assertIn("# Personal settings\nWHISPER_THREADS=3\nWHISPER_MODEL_PATH=/opt/urdu-french.bin\n", first)
        self.assertNotIn("WHISPER_FA_MODEL", os.environ)

    def test_failed_quality_setup_keeps_existing_configuration(self):
        config = self.root / "speech.env"
        config.write_text("WHISPER_THREADS=3\n", encoding="utf-8")
        with patch("setup_speech.download_persian_quality_model", return_value=self.model), \
             patch("setup_speech.WhisperCppRecognizer", side_effect=speech.OfflineSpeechError("bad model")):
            with self.assertRaisesRegex(speech.OfflineSpeechError, "bad model"):
                setup_speech.enable_persian_quality(config)
        self.assertEqual(config.read_text(encoding="utf-8"), "WHISPER_THREADS=3\n")
        self.assertNotIn("WHISPER_FA_MODEL", os.environ)
        with patch.dict(os.environ, {"WHISPER_FA_MODEL_PATH": str(self.model)}), \
             patch("setup_speech.download_persian_quality_model") as download:
            with self.assertRaisesRegex(RuntimeError, "Unset WHISPER_FA_MODEL_PATH"):
                setup_speech.enable_persian_quality(config)
            download.assert_not_called()

    def test_quality_download_is_pinned_and_corrupt_existing_files_are_rejected(self):
        with patch("setup_speech.download_model_file") as download:
            model = setup_speech.download_persian_quality_model()
        self.assertEqual(model.name, "ggml-large-v3-turbo-q5_0.bin")
        self.assertEqual(download.call_args.args[0], "ggerganov/whisper.cpp")
        self.assertEqual(download.call_args.args[3], setup_speech.PERSIAN_QUALITY_REVISION)
        self.assertEqual(download.call_args.kwargs, {"sha256": setup_speech.PERSIAN_QUALITY_SHA256})
        digest = hashlib.sha256(self.model.read_bytes()).hexdigest()
        setup_speech.download_model_file("unused", "unused", self.model, sha256=digest)
        with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
            setup_speech.download_model_file("unused", "unused", self.model, sha256="0" * 64)


if __name__ == "__main__":
    unittest.main()
