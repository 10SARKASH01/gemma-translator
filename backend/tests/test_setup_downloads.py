# Copyright 2026 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0

import errno
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import setup_speech


class SetupDownloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.blob = self.root / "cache" / "blobs" / "model-hash"
        self.blob.parent.mkdir(parents=True)
        self.blob.write_bytes(b"cached multilingual model data")
        self.destination = self.root / "models" / "whisper" / "ggml-small-q5_1.bin"
        self.hub = SimpleNamespace(hf_hub_download=Mock(return_value="cache-snapshot-link"))

    def download_from_snapshot(self):
        # Simulate HF's symlink Path while keeping this regression portable on
        # Windows hosts which cannot create symlinks without administrator rights.
        snapshot = Mock()
        snapshot.resolve.return_value = self.blob
        with patch.dict(sys.modules, {"huggingface_hub": self.hub}), \
             patch("setup_speech.Path", return_value=snapshot):
            setup_speech.download_model_file("test/models", "model.bin", self.destination)
        snapshot.resolve.assert_called_once_with(strict=True)
        self.assertEqual(self.destination.read_bytes(), self.blob.read_bytes())
        self.assertFalse(self.destination.is_symlink())
        self.assertFalse(self.destination.with_name(self.destination.name + ".part").exists())

    def test_hardlink_uses_resolved_blob_instead_of_snapshot_symlink(self):
        with patch("setup_speech.os.link", wraps=os.link) as link:
            self.download_from_snapshot()
        self.assertEqual(link.call_args.args[0], self.blob)

    def test_cross_filesystem_copy_uses_resolved_blob(self):
        with patch("setup_speech.os.link", side_effect=OSError(errno.EXDEV, "Different filesystem")):
            self.download_from_snapshot()

    def test_valid_existing_model_is_reused(self):
        self.destination.parent.mkdir(parents=True)
        self.destination.write_bytes(b"existing model")
        with patch.dict(sys.modules, {"huggingface_hub": self.hub}):
            setup_speech.download_model_file("test/models", "model.bin", self.destination)
        self.hub.hf_hub_download.assert_not_called()
        self.assertEqual(self.destination.read_bytes(), b"existing model")

    def test_real_relative_cache_symlink_and_broken_destination_are_repaired(self):
        snapshot = self.root / "cache" / "snapshots" / "revision" / "model.bin"
        snapshot.parent.mkdir(parents=True)
        self.destination.parent.mkdir(parents=True)
        try:
            snapshot.symlink_to("../../blobs/model-hash")
            self.destination.symlink_to("../../blobs/model-hash")
        except OSError as exc:
            self.skipTest(f"Host cannot create symlinks: {exc}")
        self.assertFalse(self.destination.exists())
        self.hub.hf_hub_download.return_value = str(snapshot)
        with patch.dict(sys.modules, {"huggingface_hub": self.hub}):
            setup_speech.download_model_file("test/models", "model.bin", self.destination)
        self.assertTrue(snapshot.is_symlink())
        self.assertFalse(self.destination.is_symlink())
        self.assertEqual(self.destination.read_bytes(), self.blob.read_bytes())


if __name__ == "__main__":
    unittest.main()
