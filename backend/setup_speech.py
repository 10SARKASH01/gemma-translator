# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

"""Setup-time downloads and local speech checks; never imported by the server."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil

from offline_speech import (
    MOONSHINE_STT_LANGS, MOONSHINE_TTS_LANG_MAP, MOONSHINE_TTS_VOICE_MAP,
    WhisperCppRecognizer, moonshine_tts_dir, new_fallback_tts,
    executable, run_speech_command, speech_dir,
)

PERSIAN_QUALITY_MODEL = "large-v3-turbo-q5_0"
PERSIAN_QUALITY_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
PERSIAN_QUALITY_SHA256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"


def check_model_hash(path, expected):
    if expected is None:
        return
    digest = hashlib.sha256()
    with path.open("rb") as model_file:
        for chunk in iter(lambda: model_file.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise RuntimeError(f"Model checksum mismatch: {path}. Remove this file and rerun setup.")


def download_model_file(repo, filename, destination, revision="main", *, sha256=None):
    if destination.is_file() and destination.stat().st_size:
        check_model_hash(destination, sha256)
        print(f"[Setup] Reusing {destination}", flush=True)
        return
    from huggingface_hub import hf_hub_download
    cached = Path(hf_hub_download(
        repo_id=repo, filename=filename, revision=revision,
        cache_dir=str(speech_dir() / ".download-cache"),
    )).resolve(strict=True)
    check_model_hash(cached, sha256)
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


def download_persian_quality_model():
    model = speech_dir() / "whisper" / f"ggml-{PERSIAN_QUALITY_MODEL}.bin"
    download_model_file(
        "ggerganov/whisper.cpp", model.name, model, PERSIAN_QUALITY_REVISION,
        sha256=PERSIAN_QUALITY_SHA256,
    )
    return model


def enable_persian_quality(config_path):
    """Download once and persist the Persian-only choice for startup/systemd."""
    if os.environ.get("WHISPER_FA_MODEL_PATH"):
        raise RuntimeError("Unset WHISPER_FA_MODEL_PATH in speech.env before selecting the built-in Persian quality model.")
    model = download_persian_quality_model()
    previous = os.environ.get("WHISPER_FA_MODEL")
    try:
        os.environ["WHISPER_FA_MODEL"] = PERSIAN_QUALITY_MODEL
        WhisperCppRecognizer("fa")
    finally:
        if previous is None:
            os.environ.pop("WHISPER_FA_MODEL", None)
        else:
            os.environ["WHISPER_FA_MODEL"] = previous
    settings = {"WHISPER_FA_MODEL": PERSIAN_QUALITY_MODEL, "WHISPER_FA_PROFILE": "accurate"}
    lines = config_path.read_text(encoding="utf-8").splitlines() if config_path.exists() else []
    updated = []
    for line in lines:
        assignment = line.strip().removeprefix("export ").split("=", 1)[0].strip()
        if assignment not in settings:
            updated.append(line)
    updated.extend(f"{key}={value}" for key, value in settings.items())
    temporary = config_path.with_name(config_path.name + ".part")
    temporary.write_text("\n".join(updated) + "\n", encoding="utf-8")
    temporary.replace(config_path)
    print(f"[Setup] Persian quality model ready: {model}. Saved {config_path}. Restart the app and select Accurate in Settings.", flush=True)


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
    if os.environ.get("WHISPER_FA_MODEL") == PERSIAN_QUALITY_MODEL and not os.environ.get("WHISPER_FA_MODEL_PATH"):
        download_persian_quality_model()
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
    # TTS support is independent of STT: French uses Whisper recognition but
    # Moonshine's French voice, which must also be downloaded before going offline.
    for language, moon_lang in sorted(MOONSHINE_TTS_LANG_MAP.items()):
        print(f"[Setup] Downloading Moonshine voice for {language}", flush=True)
        voice = MOONSHINE_TTS_VOICE_MAP.get(language)
        download_tts_assets(moon_lang, voice=voice, cache_root=moonshine_tts_dir())
        engine = TextToSpeech(moon_lang, voice=voice, download=False, asset_root=moonshine_tts_dir())
        try:
            if language == "fr":
                samples, rate = engine.synthesize("Bonjour, comment allez-vous ?")
                if not len(samples) or rate <= 0:
                    raise RuntimeError("No audio from French TTS")
                print(f"[Setup] fr TTS ready: moonshine-voice, {rate} Hz", flush=True)
        finally:
            engine.close()
    temporary = root / "moonshine-stt.json.part"
    temporary.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    temporary.replace(root / "moonshine-stt.json")
    print(f"[Setup] All offline speech assets ready in {root}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--persian-quality", action="store_true", help="Download/enable local Whisper Turbo for Persian only; keep other languages unchanged.")
    args = parser.parse_args()
    try:
        if args.persian_quality:
            enable_persian_quality(Path(__file__).resolve().parent.parent / "speech.env")
        else:
            setup_speech()
    except Exception as exc:
        raise SystemExit(f"[Setup Error] {exc}") from exc
