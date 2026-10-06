# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

"""Warm the existing local Gemma engine once before exposing the translator UI."""

import json
import os
import time
import urllib.request


def warmup_gemma():
    if os.environ.get("GEMMA_WARMUP", "1") == "0":
        return True
    model = os.environ.get("GEMMA_MODEL_NAME", "gemma4-e2b")
    try:
        timeout = int(os.environ.get("GEMMA_WARMUP_TIMEOUT_SECONDS", "120"))
        if timeout <= 0:
            raise ValueError("GEMMA_WARMUP_TIMEOUT_SECONDS must be positive")
        payload = json.dumps({
            "model": model,
            "messages": [{"role": "user", "content": "Reply only OK."}],
            "temperature": 0,
        }).encode("utf-8")
        request = urllib.request.Request(
            "http://127.0.0.1:9379/v1/chat/completions", data=payload,
            headers={"Content-Type": "application/json"}, method="POST",
        )
        # A local model warmup must not use a configured internet HTTP proxy.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        started = time.perf_counter()
        print(f"[Warmup] Loading local Gemma model={model}...", flush=True)
        with opener.open(request, timeout=timeout) as response:
            result = json.loads(response.read())
        if not result.get("choices"):
            raise ValueError("Local Gemma returned no completion")
        print(f"[Warmup] Gemma ready in {time.perf_counter() - started:.2f}s", flush=True)
        return True
    except Exception as exc:
        # Warmup shifts cold-start latency; failure must not disable normal use.
        print(f"[Warmup Warning] Gemma warmup failed: {exc}. The first translation will load it.", flush=True)
        return False


if __name__ == "__main__":
    warmup_gemma()
