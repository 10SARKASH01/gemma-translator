# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

"""Exercise the persistent worker over real loopback HTTP, without native models."""

from email import policy
from email.parser import BytesParser
import http.server
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import wave

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import offline_speech as speech
import whisper_server as workers


class WhisperServerTests(unittest.TestCase):
    def setUp(self):
        workers.close_whisper_server()
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.model = self.root / "multilingual.bin"
        self.model.write_bytes(struct.pack("<II", 0x67676D6C, 51865))
        self.environment = patch.dict(os.environ, {
            "WHISPER_MODE": "server", "WHISPER_SERVER_BINARY": str(self.root / "whisper-server"),
            "WHISPER_CPP_BINARY": str(self.root / "whisper-cli"),
            "WHISPER_MODEL_PATH": str(self.model), "WHISPER_THREADS": "4",
            "SPEECH_TIMEOUT_SECONDS": "3",
        })
        self.environment.start()
        self.executables = patch("offline_speech.shutil.which", side_effect=lambda value: value)
        self.executables.start()
        self.requests = []
        self.processes = []
        self.reply = {"text": " سلام "}
        self.http_status = 200
        self.behavior = "ready"
        self.inference_delay = 0
        self.popen = patch("whisper_server.subprocess.Popen", side_effect=self.make_process)
        self.popen_mock = self.popen.start()

    def tearDown(self):
        workers.close_whisper_server()
        for process in self.processes:
            process.terminate()
        self.popen.stop()
        self.executables.stop()
        self.environment.stop()
        self.temporary.cleanup()

    def make_process(self, command, **kwargs):
        owner = self
        request_path = command[command.index("--request-path") + 1]
        port = int(command[command.index("--port") + 1])

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def respond(self, status, result):
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                try:
                    self.wfile.write(json.dumps(result).encode("utf-8"))
                except OSError:
                    pass

            def do_GET(self):
                if self.path != request_path + "/health":
                    return self.respond(404, {"error": "wrong worker path"})
                if owner.behavior == "loading":
                    return self.respond(503, {"status": "loading model"})
                self.respond(200, {"status": "ok"})

            def do_POST(self):
                length = int(self.headers["Content-Length"])
                body = self.rfile.read(length)
                message = BytesParser(policy=policy.default).parsebytes(
                    ("Content-Type: " + self.headers["Content-Type"] + "\r\n\r\n").encode() + body,
                )
                fields = {
                    part.get_param("name", header="content-disposition"): part.get_payload(decode=True)
                    for part in message.iter_parts()
                }
                owner.requests.append(fields)
                if owner.inference_delay:
                    time.sleep(owner.inference_delay)
                self.respond(owner.http_status, owner.reply)

        class FakeProcess:
            def __init__(self):
                self.returncode = None
                self.server = None
                self.thread = None
                self.terminated = 0
                self.killed = 0
                if owner.behavior == "exit":
                    kwargs["stdout"].write(b"invalid model header\n")
                    kwargs["stdout"].flush()
                    self.returncode = 3
                else:
                    self.server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
                    self.server.daemon_threads = True
                    self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
                    self.thread.start()

            def poll(self):
                return self.returncode

            def terminate(self):
                if self.returncode is None:
                    self.terminated += 1
                    self.returncode = -15
                    if self.server is not None:
                        self.server.shutdown()
                        self.server.server_close()

            def kill(self):
                self.killed += 1
                self.terminate()

            def wait(self, timeout=None):
                if self.thread is not None:
                    self.thread.join(timeout)
                return self.returncode

        process = FakeProcess()
        self.processes.append(process)
        return process

    def recognizer(self, language="fa"):
        return speech.WhisperCppRecognizer(language)

    def test_fa_ur_and_fr_share_one_worker_and_keep_existing_pcm_contract(self):
        pcm = np.array([0, 0.5, -0.5, 2], dtype=np.float32)
        for language, expected in [("fa", "سلام"), ("ur", "آپ کیسے ہیں؟"), ("fr", "Bonjour")]:
            self.reply = {"text": " " + expected + " "}
            result = self.recognizer(language).transcribe_without_streaming(pcm, 16000)
            self.assertEqual(result.lines[0].text, expected)
        self.assertEqual(self.popen_mock.call_count, 1)
        for fields, language in zip(self.requests, [b"fa", b"ur", b"fr"]):
            self.assertEqual(fields["language"], language)
            self.assertEqual(fields["translate"], b"false")
            self.assertEqual(fields["detect_language"], b"false")
            self.assertEqual(fields["response_format"], b"json")
            self.assertEqual(fields["no_timestamps"], b"true")
            with wave.open(io.BytesIO(fields["file"]), "rb") as wav:
                self.assertEqual((wav.getnchannels(), wav.getsampwidth(), wav.getframerate()), (1, 2, 16000))
                samples = np.frombuffer(wav.readframes(4), dtype="<i2")
                self.assertEqual(samples.tolist(), [0, 16383, -16383, 32767])
        command = self.popen_mock.call_args.args[0]
        self.assertEqual(command[command.index("--host") + 1], "127.0.0.1")
        self.assertEqual(command[command.index("-bs") + 1], "5")
        self.assertEqual(command[command.index("-bo") + 1], "5")
        self.assertIn("-ng", command)
        self.assertNotIn("--convert", command)

    def test_warmup_starts_once_without_transcribing(self):
        self.recognizer("fa").warmup()
        self.recognizer("ur").warmup()
        self.assertEqual(self.popen_mock.call_count, 1)
        self.assertEqual(self.requests, [])

    def test_rollback_ignores_saved_persian_profiles_and_turbo_override(self):
        default_model = self.root / "whisper" / "ggml-small-q5_1.bin"
        default_model.parent.mkdir()
        default_model.write_bytes(self.model.read_bytes())
        # A Pi may still export these values from the newer speech.env or
        # systemd EnvironmentFile. They must not reactivate the reverted path.
        with patch.dict(os.environ, {
            "OFFLINE_SPEECH_DIR": str(self.root), "WHISPER_MODE": "server",
            "WHISPER_SERVER_BINARY": str(self.root / "whisper-server"),
            "WHISPER_THREADS": "4", "SPEECH_TIMEOUT_SECONDS": "3",
            "WHISPER_FA_MODEL": "large-v3-turbo-q5_0",
            "WHISPER_FA_MODEL_PATH": str(self.root / "missing-turbo.bin"),
            "WHISPER_FA_PROFILE": "fast",
        }, clear=True):
            for language in ["fa", "ur", "fr"]:
                recognizer = self.recognizer(language)
                self.assertEqual(recognizer.model, default_model)
                result = recognizer.transcribe_without_streaming(np.zeros(16000, dtype=np.float32), 16000)
                self.assertEqual(result.lines[0].text, "سلام")
        self.assertEqual(self.popen_mock.call_count, 1)
        command = self.popen_mock.call_args.args[0]
        self.assertEqual(command[command.index("-m") + 1], str(default_model))
        self.assertEqual(command[command.index("-bs") + 1], "5")
        self.assertEqual([request["language"] for request in self.requests], [b"fa", b"ur", b"fr"])

    def test_auto_uses_server_when_present_and_cli_when_absent(self):
        with patch.dict(os.environ, {"WHISPER_MODE": "auto"}):
            self.assertEqual(self.recognizer().mode, "server")
            with patch("offline_speech.shutil.which", side_effect=lambda value: None if "server" in value else value), \
                 patch("builtins.print") as log:
                speech._warned_whisper_cli = False
                self.assertEqual(self.recognizer("fa").mode, "cli")
                self.assertEqual(self.recognizer("ur").mode, "cli")
                self.assertEqual(log.call_count, 1)
        self.popen_mock.assert_not_called()

    def test_explicit_server_missing_and_invalid_mode_are_actionable(self):
        with patch("offline_speech.shutil.which", return_value=None):
            with self.assertRaisesRegex(speech.OfflineSpeechError, "WHISPER_SERVER_BINARY"):
                self.recognizer()
        with patch.dict(os.environ, {"WHISPER_MODE": "cloud"}):
            with self.assertRaisesRegex(speech.OfflineSpeechError, "auto, server or cli"):
                self.recognizer()

    def test_cli_warmup_does_not_spawn_worker(self):
        with patch.dict(os.environ, {"WHISPER_MODE": "cli"}):
            recognizer = self.recognizer()
            self.assertEqual(recognizer.mode, "cli")
            recognizer.warmup()
        self.popen_mock.assert_not_called()

    def test_english_only_model_is_rejected_before_launch(self):
        self.model.write_bytes(struct.pack("<II", 0x67676D6C, 51864))
        with self.assertRaisesRegex(speech.OfflineSpeechError, "multilingual"):
            self.recognizer()
        self.popen_mock.assert_not_called()

    def test_worker_exit_before_ready_reports_native_error_and_cleans_logs(self):
        self.behavior = "exit"
        worker = workers.get_whisper_server("whisper-server", self.model, 4, 1)
        with self.assertRaisesRegex(speech.OfflineSpeechError, "invalid model header"):
            worker.warmup()
        self.assertIsNone(worker._process)
        self.assertIsNone(worker._temporary)

    def test_missing_process_binary_is_actionable(self):
        self.popen_mock.side_effect = FileNotFoundError("missing native executable")
        with self.assertRaisesRegex(speech.OfflineSpeechError, "WHISPER_SERVER_BINARY"):
            self.recognizer().warmup()

    def test_startup_timeout_and_shutdown_cancellation_stop_process(self):
        self.behavior = "loading"
        worker = workers.get_whisper_server("whisper-server", self.model, 4, 0.2)
        with self.assertRaisesRegex(speech.OfflineSpeechError, "not ready"):
            worker.warmup()
        self.assertEqual(self.processes[0].terminated, 1)

        worker = workers.get_whisper_server("whisper-server", self.model, 4, 10)
        failures = []
        started = threading.Event()

        def load():
            started.set()
            try:
                worker.warmup()
            except speech.OfflineSpeechError as exc:
                failures.append(str(exc))

        thread = threading.Thread(target=load)
        thread.start()
        self.assertTrue(started.wait(1))
        deadline = time.monotonic() + 2
        while len(self.processes) < 2 and time.monotonic() < deadline:
            time.sleep(0.01)
        workers.close_whisper_server()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertTrue(failures and "cancelled" in failures[0])
        self.assertEqual(self.processes[-1].terminated, 1)

    def test_dead_worker_restarts_on_next_request(self):
        recognizer = self.recognizer()
        pcm = np.ones(16, dtype=np.float32)
        recognizer.transcribe_without_streaming(pcm, 16000)
        self.processes[0].terminate()
        recognizer.transcribe_without_streaming(pcm, 16000)
        self.assertEqual(self.popen_mock.call_count, 2)

    def test_close_interrupts_active_inference_without_waiting_for_request_lock(self):
        worker = workers.get_whisper_server("whisper-server", self.model, 4, 3)
        self.inference_delay = 1.5
        failures = []

        def recognize():
            try:
                worker.transcribe(b"wav", "fa")
            except speech.OfflineSpeechError as exc:
                failures.append(str(exc))

        thread = threading.Thread(target=recognize)
        thread.start()
        deadline = time.monotonic() + 2
        while not self.requests and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.requests)
        closing = time.monotonic()
        workers.close_whisper_server()
        self.assertLess(time.monotonic() - closing, 1)
        thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertTrue(failures and "cancelled" in failures[0])
        self.assertEqual(self.processes[0].terminated, 1)

    def test_invalid_success_and_native_http_errors_are_reported(self):
        recognizer = self.recognizer()
        pcm = np.ones(16, dtype=np.float32)
        for reply in [{"error": "bad WAV"}, {"text": None}, []]:
            self.reply = reply
            with self.assertRaisesRegex(speech.OfflineSpeechError, "invalid transcript"):
                recognizer.transcribe_without_streaming(pcm, 16000)
        self.http_status, self.reply = 500, {"error": "native inference failed"}
        with self.assertRaisesRegex(speech.OfflineSpeechError, "native inference failed"):
            recognizer.transcribe_without_streaming(pcm, 16000)

    def test_inference_timeout_stops_cpu_work_and_next_request_restarts(self):
        worker = workers.get_whisper_server("whisper-server", self.model, 4, 0.1)
        self.inference_delay = 0.3
        with self.assertRaisesRegex(speech.OfflineSpeechError, "next request will restart"):
            worker.transcribe(b"wav", "ur")
        self.assertEqual(self.processes[0].terminated, 1)
        self.inference_delay = 0
        self.assertEqual(worker.transcribe(b"wav", "ur"), "سلام")
        self.assertEqual(self.popen_mock.call_count, 2)

    def test_close_is_idempotent_removes_logs_and_new_worker_can_start(self):
        self.recognizer().warmup()
        worker = workers._worker
        log_directory = Path(worker._temporary.name)
        workers.close_whisper_server()
        workers.close_whisper_server()
        self.assertFalse(log_directory.exists())
        self.assertEqual(self.processes[0].terminated, 1)
        self.recognizer("ur").warmup()
        self.assertEqual(self.popen_mock.call_count, 2)

    def test_final_shutdown_prevents_late_requests_from_recreating_worker(self):
        with patch.object(workers, "_shutdown_requested", False):
            self.recognizer().warmup()
            speech.close_whisper_server(shutdown=True)
            with self.assertRaisesRegex(speech.OfflineSpeechError, "shutting down"):
                self.recognizer("ur").warmup()
            self.assertEqual(self.popen_mock.call_count, 1)

    def test_native_log_is_bounded_between_requests_and_retains_recent_errors(self):
        recognizer = self.recognizer()
        recognizer.warmup()
        path = Path(workers._worker._temporary.name) / "server.log"
        path.write_bytes(b"old diagnostics\n" * 100000 + b"latest native message\n")
        recognizer.transcribe_without_streaming(np.ones(16, dtype=np.float32), 16000)
        self.assertEqual(path.stat().st_size, 4096)
        self.assertTrue(path.read_bytes().endswith(b"latest native message\n"))


if __name__ == "__main__":
    unittest.main()
