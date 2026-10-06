# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

"""Setup-time downloads and local speech checks; never imported by the server."""

import json
import os
from pathlib import Path
import shutil

from offline_speech import (
    MOONSHINE_STT_LANGS, MOONSHINE_TTS_LANG_MAP, MOONSHINE_TTS_VOICE_MAP,
    WhisperCppRecognizer, moonshine_tts_dir, new_fallback_tts,
    executable, run_speech_command, speech_dir,
)


def download_model_file(repo, filename, destination, revision="main"):
    if destination.is_file() and destination.stat().st_size:
        print(f"[Setup] Reusing {destination}", flush=True)
        return
    from huggingface_hub import hf_hub_download
    cached = Path(hf_hub_download(
        repo_id=repo, filename=filename, revision=revision,
        cache_dir=str(speech_dir() / ".download-cache"),
    )).resolve(strict=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Keep HF's download verification/cache while avoiding a duplicate model
    # allocation on the Pi when the destination is on the same filesystem.
    # HF snapshot paths are relative symlinks into its blobs directory. Link
    # the resolved data file, never the symlink (which breaks at our destination).
    temporary = destination.with_name(destination.name + ".part")
    temporary.unlink(missing_ok=True)
    try:
        os.link(cached, temporary)
    except OSError:
        shutil.copyfile(cached, temporary)
    temporary.replace(destination)


def setup_speech():
    from moonshine_voice import get_model_for_language, download_tts_assets, TextToSpeech
    root = speech_dir()
    root.mkdir(parents=True, exist_ok=True)
    # Keep destination names intact so rerunning setup can replace broken
    # symlinks left by an older downloader, rather than following their targets.
    whisper_model = Path(os.environ.get(
        "WHISPER_MODEL_PATH", root / "whisper" / "ggml-small-q5_1.bin",
    )).expanduser().absolute()
    if "WHISPER_MODEL_PATH" in os.environ and not whisper_model.is_file():
        raise RuntimeError(
            f"WHISPER_MODEL_PATH does not exist: {whisper_model}. Point it to an "
            "existing multilingual GGML model, or unset it to download small-q5_1."
        )
    download_model_file(
        "ggerganov/whisper.cpp", "ggml-small-q5_1.bin", whisper_model,
    )
    WhisperCppRecognizer("fa")  # Validate the multilingual model and runtime mode.
    for setting, name in [
        ("WHISPER_CPP_BINARY", "whisper-cli"), ("WHISPER_SERVER_BINARY", "whisper-server"),
    ]:
        binary = executable(setting, root / "whisper.cpp" / "build" / "bin" / name)
        run_speech_command([binary, "--help"], timeout=30)

    if os.environ.get("PERSIAN_TTS_ENGINE", "piper") == "piper":
        model = Path(os.environ.get(
            "PIPER_FA_MODEL", root / "piper" / "fa_IR-amir-medium.onnx",
        )).expanduser().absolute()
        config = Path(os.environ.get("PIPER_FA_CONFIG", str(model) + ".json")).expanduser().absolute()
        if "PIPER_FA_MODEL" in os.environ or "PIPER_FA_CONFIG" in os.environ:
            if not model.is_file() or not config.is_file():
                raise RuntimeError("Custom PIPER_FA_MODEL/PIPER_FA_CONFIG must both exist.")
        else:
            prefix = "fa/fa_IR/amir/medium/"
            # Pin this voice bundle so ONNX and config stay matched.
            revision = "c10ece1aade47bb51c153c893d14e5bf8e5b7117"
            for remote_name, destination in [
                (model.name, model), (config.name, config),
                ("MODEL_CARD", model.parent / "MODEL_CARD"),
            ]:
                download_model_file("rhasspy/piper-voices", prefix + remote_name, destination, revision)

    for language, text in [("fa", "سلام، حال شما چطور است؟"), ("ur", "السلام علیکم، آپ کیسے ہیں؟")]:
        engine = new_fallback_tts(language)
        samples, rate = engine.synthesize(text)
        if not len(samples) or rate <= 0:
            raise RuntimeError(f"No audio from {language} TTS")
        print(f"[Setup] {language} TTS ready: {engine.engine_name}, {rate} Hz", flush=True)
        del engine

    # Existing languages must also be cached before disconnecting the appliance.
    # Runtime reads this manifest and constructs TTS with download=False.
    manifest = {}
    for language in sorted(MOONSHINE_STT_LANGS):
        print(f"[Setup] Downloading Moonshine assets for {language}", flush=True)
        model_path, model_arch = get_model_for_language(
            language, cache_root=root / "moonshine" / "stt",
        )
        manifest[language] = {"model_path": str(Path(model_path).resolve()), "model_arch": model_arch.value}
        moon_lang = MOONSHINE_TTS_LANG_MAP[language]
        voice = MOONSHINE_TTS_VOICE_MAP.get(language)
        download_tts_assets(moon_lang, voice=voice, cache_root=moonshine_tts_dir())
        engine = TextToSpeech(moon_lang, voice=voice, download=False, asset_root=moonshine_tts_dir())
        engine.close()
    temporary = root / "moonshine-stt.json.part"
    temporary.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    temporary.replace(root / "moonshine-stt.json")
    print(f"[Setup] All offline speech assets ready in {root}", flush=True)


if __name__ == "__main__":
    try:
        setup_speech()
    except Exception as exc:
        raise SystemExit(f"[Setup Error] {exc}") from exc
