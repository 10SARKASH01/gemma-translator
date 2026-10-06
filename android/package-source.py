#!/usr/bin/env python3
"""Package matching Android source and verified native dependency source with an APK."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request
import zipfile


ANDROID = Path(__file__).resolve().parent


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def dependency_source(spec, cache):
    if Path(spec["file"]).name != spec["file"] or not spec["url"].startswith("https://"):
        raise ValueError("Invalid source dependency catalog")
    destination = cache / spec["file"]
    if destination.is_file() and destination.stat().st_size == spec["bytes"] and sha256(destination) == spec["sha256"]:
        return destination
    partial = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(spec["url"], headers={"User-Agent": "GemmaTranslator-SourcePackage/1.0"})
    print(f"Downloading source: {spec['id']}", flush=True)
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
        received = 0
        while block := response.read(1024 * 1024):
            received += len(block)
            if received > spec["bytes"]:
                raise ValueError(f"Source size exceeded catalog: {spec['id']}")
            output.write(block)
    if partial.stat().st_size != spec["bytes"] or sha256(partial) != spec["sha256"]:
        partial.unlink(missing_ok=True)
        raise ValueError(f"Source failed size/SHA-256 verification: {spec['id']}")
    partial.replace(destination)
    return destination


def application_files():
    # Prune build/download/private files before traversing, including local SDK paths.
    excluded_dirs = {".git", ".gradle", ".kotlin", "build", "dist", "libs", "__pycache__"}
    for folder, dirs, files in os.walk(ANDROID):
        dirs[:] = sorted(name for name in dirs if name not in excluded_dirs)
        for name in sorted(files):
            path = Path(folder) / name
            if name == "local.properties" or path.suffix.lower() in {".aar", ".apk", ".jks", ".keystore", ".part"}:
                continue
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "gemma-translator-source-cache")
    parser.add_argument("--output", type=Path, default=ANDROID / "dist/gemma-translator-s24-corresponding-source.zip")
    parser.add_argument("--apk", type=Path, default=ANDROID / "dist/gemma-translator-s24-debug.apk")
    args = parser.parse_args()
    catalog = json.loads((ANDROID / "source-dependencies.json").read_text(encoding="utf-8"))
    args.cache.mkdir(parents=True, exist_ok=True)
    archives = [(spec, dependency_source(spec, args.cache)) for spec in catalog]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".zip.part")
    manifest = {"dependencies": catalog, "application": {}}
    if args.apk.is_file():
        manifest["apk"] = {"file": args.apk.name, "sha256": sha256(args.apk), "bytes": args.apk.stat().st_size}
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as bundle:
        for path in application_files():
            relative = "application/android/" + path.relative_to(ANDROID).as_posix()
            bundle.write(path, relative)
            manifest["application"][relative] = sha256(path)
        for name in ("LICENSE", "README.md", ".gitattributes"):
            path = ANDROID.parent / name
            bundle.write(path, "application/" + name)
        for spec, archive in archives:
            bundle.write(archive, "upstream/" + spec["file"], compress_type=zipfile.ZIP_STORED)
        bundle.writestr("SOURCE_MANIFEST.json", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
        bundle.writestr("BUILD_SOURCE.txt", (
            "This source archive accompanies the Gemma Translator Android APK.\n"
            "Application: unpack application/, install JDK 21 and Android SDK 35,\n"
            "then run android/build-apk.ps1 or bash android/build-apk.sh.\n"
            "See application/android/README.md and THIRD_PARTY_NOTICES.md.\n"
            "Native speech: unpack upstream/sherpa-onnx.zip and its dependency archives.\n"
            "The pinned upstream build-android-arm64-v8a.sh and .github/workflows/android.yaml\n"
            "specify native compilation/AAR packaging. Follow them with an Android NDK.\n"
            "If replacing the speech AAR, update its checksum in app/build.gradle.kts.\n"
            "Upstream archives are unmodified; SHA-256 provenance is in SOURCE_MANIFEST.json.\n"
            "No user data, signing keys, model weights, SDK paths, or build caches are included.\n"
        ))
    temporary.replace(args.output)
    print(f"Corresponding source ready: {args.output} ({args.output.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
