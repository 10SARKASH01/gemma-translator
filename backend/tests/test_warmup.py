# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server
import warmup


class WarmupTests(unittest.TestCase):
    def test_gemma_uses_one_small_local_inference_and_configured_model(self):
        response = Mock()
        response.read.return_value = b'{"choices":[{"message":{"content":"OK"}}]}'
        opener = Mock()
        opener.open.return_value.__enter__ = Mock(return_value=response)
        opener.open.return_value.__exit__ = Mock(return_value=False)
        with patch.dict(os.environ, {"GEMMA_WARMUP": "1", "GEMMA_MODEL_NAME": "gemma4-e2b,cpu",
                                     "GEMMA_WARMUP_TIMEOUT_SECONDS": "90"}), \
             patch("warmup.urllib.request.build_opener", return_value=opener):
            self.assertTrue(warmup.warmup_gemma())
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "http://127.0.0.1:9379/v1/chat/completions")
        payload = json.loads(request.data)
        self.assertEqual(payload["model"], "gemma4-e2b,cpu")
        self.assertEqual(payload["temperature"], 0)
        self.assertEqual(payload["messages"], [{"role": "user", "content": "Reply only OK."}])
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 90)

    def test_disabled_warmup_does_not_make_requests(self):
        with patch.dict(os.environ, {"GEMMA_WARMUP": "0"}), \
             patch("warmup.urllib.request.build_opener") as build:
            self.assertTrue(warmup.warmup_gemma())
        build.assert_not_called()

    def test_warmup_failure_does_not_stop_normal_startup(self):
        opener = Mock()
        opener.open.side_effect = TimeoutError("model loading timed out")
        with patch.dict(os.environ, {"GEMMA_WARMUP": "1"}), \
             patch("warmup.urllib.request.build_opener", return_value=opener):
            self.assertFalse(warmup.warmup_gemma())

    def test_selected_pair_prewarms_recognition_and_voices(self):
        recognizer = Mock()
        with patch.dict(os.environ, {"SPEECH_PREWARM_LANGUAGES": "fa,en"}), \
             patch("server.get_stt_recognizer", return_value=recognizer) as stt, \
             patch("server.get_tts_engine") as tts:
            server.prewarm_speech_models()
        self.assertEqual([call.args[0] for call in stt.call_args_list], ["fa", "en"])
        self.assertEqual([call.args[0] for call in tts.call_args_list], ["fa", "en"])
        self.assertEqual(recognizer.warmup.call_count, 2)

    def test_disabled_or_oversized_prewarm_keeps_memory_bounded(self):
        for languages in ["", "fa,ur,en", "invalid"]:
            with self.subTest(languages=languages), \
                 patch.dict(os.environ, {"SPEECH_PREWARM_LANGUAGES": languages}), \
                 patch("server.get_stt_recognizer") as stt, \
                 patch("server.get_tts_engine") as tts:
                server.prewarm_speech_models()
            stt.assert_not_called()
            tts.assert_not_called()


if __name__ == "__main__":
    unittest.main()
