# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

"""One local whisper.cpp worker shared by French, Persian and Urdu recognizers."""

import atexit
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

from offline_speech import OfflineSpeechError, WHISPER_STT_LANGS


def multipart_audio(wav_bytes, language, options=None):
    boundary = "gemma-" + uuid.uuid4().hex
    parts = []
    for name, value in {
        "language": language, "translate": "false", "detect_language": "false",
        "response_format": "json", "no_timestamps": "true",
        # Explicitly restore every decoding field each request: the native
        # worker is shared across languages and profiles.
        "beam_size": 5, "best_of": 5, "audio_ctx": 0,
        **(options or {}),
    }.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'
            f'{value}\r\n'.encode("utf-8")
        )
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        'filename="recording.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode("utf-8")
    )
    parts.extend([wav_bytes, f"\r\n--{boundary}--\r\n".encode("ascii")])
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


class WhisperServer:
    def __init__(self, binary, model, threads, timeout):
        self.binary, self.model = str(binary), str(model)
        self.threads, self.timeout = threads, timeout
        self._request_lock = threading.RLock()
        self._process_lock = threading.Lock()
        self._stopping = threading.Event()
        self._process = None
        self._temporary = None
        self._ready = False
        self._base_url = None
        # Local inference must not be sent through a configured internet proxy.
        self._http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def _check_cancelled(self):
        if self._stopping.is_set():
            raise OfflineSpeechError("Whisper startup/request cancelled because the backend is shutting down.")

    def _log_tail(self):
        try:
            return (Path(self._temporary.name) / "server.log").read_bytes()[-1000:].decode(
                "utf-8", errors="replace",
            ).strip()
        except (AttributeError, OSError):
            return ""

    def _trim_log(self):
        try:
            path = Path(self._temporary.name) / "server.log"
            if path.stat().st_size > 1024 * 1024:
                with path.open("r+b") as log:
                    log.seek(-4096, os.SEEK_END)
                    tail = log.read()
                    log.seek(0)
                    log.write(tail)
                    log.truncate()
        except (AttributeError, OSError):
            pass  # Shutdown may already have removed the temporary log.

    def _terminate(self):
        with self._process_lock:
            process, self._process = self._process, None
            self._ready = False
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if self._temporary is not None:
                self._temporary.cleanup()
                self._temporary = None

    def close(self):
        # This can interrupt startup while another thread holds _request_lock.
        self._stopping.set()
        self._terminate()

    def _start(self):
        self._check_cancelled()
        self._terminate()
        with self._process_lock:
            self._check_cancelled()
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            request_path = "/gemma-" + uuid.uuid4().hex
            self._base_url = f"http://127.0.0.1:{port}{request_path}"
            self._temporary = tempfile.TemporaryDirectory(prefix="gemma-whisper-server-")
            command = [
                self.binary, "-m", self.model, "-t", str(self.threads), "-ng", "-nt",
                # Match whisper-cli's decoding defaults rather than changing accuracy.
                "-bs", "5", "-bo", "5", "--host", "127.0.0.1", "--port", str(port),
                "--request-path", request_path,
            ]
            try:
                # O_APPEND lets us truncate an oversized diagnostic log between
                # requests without redirecting subsequent native writes past EOF.
                with (Path(self._temporary.name) / "server.log").open("ab") as log:
                    self._process = subprocess.Popen(
                        command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                    )
            except OSError as exc:
                self._temporary.cleanup()
                self._temporary = None
                raise OfflineSpeechError(
                    f"Could not start whisper-server: {exc}. Run ./setup.sh or set WHISPER_SERVER_BINARY."
                ) from exc
        print(f"[STT] Starting shared whisper.cpp server model={Path(self.model).name} (one multilingual model)", flush=True)

    def _ensure_ready(self):
        self._check_cancelled()
        process = self._process
        if self._ready and process is not None and process.poll() is None:
            return
        self._start()
        deadline = time.monotonic() + self.timeout
        try:
            while time.monotonic() < deadline:
                self._check_cancelled()
                process = self._process
                if process is None or process.poll() is not None:
                    self._check_cancelled()
                    detail = self._log_tail()
                    raise OfflineSpeechError(
                        f"whisper-server exited before becoming ready. {detail} "
                        "Check the multilingual model and run ./setup.sh to rebuild whisper-server."
                    )
                try:
                    with self._http.open(self._base_url + "/health", timeout=min(1, self.timeout)) as response:
                        health = json.loads(response.read().decode("utf-8"))
                        ready = isinstance(health, dict) and health.get("status") == "ok"
                    if ready:
                        self._check_cancelled()
                        self._ready = True
                        return
                except (OSError, ValueError, urllib.error.URLError):
                    pass
                self._stopping.wait(0.1)
            raise OfflineSpeechError(
                f"whisper-server was not ready after {self.timeout}s. {self._log_tail()} "
                "Check WHISPER_SERVER_BINARY/WHISPER_MODEL_PATH or increase SPEECH_TIMEOUT_SECONDS."
            )
        except Exception:
            self._terminate()
            raise

    def warmup(self):
        with self._request_lock:
            self._ensure_ready()

    def transcribe(self, wav_bytes, language, *, options=None):
        if language not in WHISPER_STT_LANGS:
            raise ValueError(f"Whisper server fallback does not handle {language}")
        with self._request_lock:
            self._ensure_ready()
            body, content_type = multipart_audio(wav_bytes, language, options)
            request = urllib.request.Request(
                self._base_url + "/inference", body, {"Content-Type": content_type}, method="POST",
            )
            try:
                with self._http.open(request, timeout=self.timeout) as response:
                    result = json.loads(response.read().decode("utf-8"))
                self._check_cancelled()
                if not isinstance(result, dict) or not isinstance(result.get("text"), str):
                    detail = result.get("error", "missing text field") if isinstance(result, dict) else "invalid response"
                    raise OfflineSpeechError(f"whisper-server returned an invalid transcript: {detail}")
                return result["text"].strip()
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode("utf-8", errors="replace")[-600:]
                raise OfflineSpeechError(f"whisper-server transcription failed ({exc.code}): {detail}") from exc
            except (OSError, ValueError, urllib.error.URLError) as exc:
                # Stop timed-out work instead of leaving it consuming the Pi's CPU.
                self._terminate()
                self._check_cancelled()
                raise OfflineSpeechError(
                    f"whisper-server transcription failed: {exc}. Check the model or "
                    "increase SPEECH_TIMEOUT_SECONDS; the next request will restart the worker."
                ) from exc
            finally:
                self._trim_log()


_worker = None
_worker_key = None
_worker_lock = threading.Lock()
_shutdown_requested = False


def get_whisper_server(binary, model, threads, timeout):
    global _worker, _worker_key
    key = (str(binary), str(model), threads, timeout)
    with _worker_lock:
        if _shutdown_requested:
            raise OfflineSpeechError("Whisper startup/request cancelled because the backend is shutting down.")
        if _worker is None or _worker_key != key:
            if _worker is not None:
                _worker.close()
            _worker = WhisperServer(*key)
            _worker_key = key
        return _worker


def close_whisper_server(shutdown=False):
    global _worker, _worker_key, _shutdown_requested
    with _worker_lock:
        _shutdown_requested = _shutdown_requested or shutdown
        worker, _worker = _worker, None
        _worker_key = None
    if worker is not None:
        worker.close()


atexit.register(close_whisper_server, shutdown=True)
