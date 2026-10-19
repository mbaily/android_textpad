"""Exercise release cleanup and APK activation without touching the host."""
from contextlib import redirect_stdout
import hashlib
from importlib.util import module_from_spec, spec_from_file_location
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = spec_from_file_location("downloads", Path(__file__).parents[1] / "deploy/install-downloads.py")
installer = module_from_spec(spec)
spec.loader.exec_module(installer)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name) / "app"
        self.releases = self.base / "releases"
        self.releases.mkdir(parents=True)
        (self.base / "config-backups").mkdir()
        self.old = []
        for day in range(1, 7):
            release = self.releases / f"202501{day:02d}T000000000000Z-0123456789ab"
            release.mkdir()
            (release / "old.apk").write_bytes(b"old apk")
            (self.base / "config-backups" / release.name).mkdir()
            self.old.append(release)
        (self.base / "deployment").symlink_to(self.old[-1].relative_to(self.base))

    def test_retention_preserves_active_rollback_and_unrecognized_directories(self):
        special = self.releases / "manual-recovery"
        special.mkdir()
        (self.releases / "20240101T000000000000Z").symlink_to(special)
        removed = installer.prune_releases(self.releases, protected=[self.old[0]])
        self.assertEqual(len(removed), 2)
        for release in [self.old[0], *self.old[-3:], special]:
            self.assertTrue(release.exists())
        self.assertFalse(self.old[1].exists())
        self.assertFalse((self.base / "config-backups" / self.old[1].name).exists())

    def test_server_cleanup_matches_configuration_backups_and_preserves_data(self):
        root = self.base / "server-releases"
        root.mkdir()
        for day in range(1, 5):
            stamp = f"202501{day:02d}T000000000000Z"
            (root / stamp).mkdir()
            (self.base / "config-backups" / ("server-" + stamp)).mkdir()
        data = self.base / "data/backups"
        data.mkdir(parents=True)
        (data / "precious.sqlite3").write_bytes(b"user data")
        installer.prune_releases(root, backup_prefix="server-")
        self.assertEqual(len(list(root.iterdir())), 3)
        self.assertFalse((self.base / "config-backups/server-20250101T000000000000Z").exists())
        self.assertEqual((data / "precious.sqlite3").read_bytes(), b"user data")

    def test_symlinked_release_root_is_rejected(self):
        link = self.base / "linked-releases"
        link.symlink_to(self.releases)
        with self.assertRaisesRegex(RuntimeError, "symlinked"):
            installer.prune_releases(link)
        self.assertEqual(len(list(self.releases.iterdir())), 6)

    def activate(self, *, fail=False):
        stage = Path(self.temp.name) / "stage"
        site = stage / "site"
        (site / "downloads").mkdir(parents=True)
        filename = "textpad-1.0-debug-0123456789ab.apk"
        apk = b"test APK bytes"
        metadata = {"filename": filename, "version": "1.0", "sha256": hashlib.sha256(apk).hexdigest(), "size_bytes": len(apk)}
        (site / "release.json").write_text(json.dumps(metadata))
        (site / "index.html").write_text(filename)
        (site / "site.css").write_text("body {}")
        for name in ["textpad-latest.apk", "downloads/" + filename]:
            (site / name).write_bytes(apk)
            (site / (name + ".sha256")).write_text(metadata["sha256"])
        paths = {}
        for name in ["HTTPS_VHOST", "HTTP_VHOST", "HTTPS_INCLUDE", "HTTP_INCLUDE"]:
            paths[name] = Path(self.temp.name) / name
            paths[name].write_text("<VirtualHost *:443>\n</VirtualHost>\n")
        for name in ["downloads.conf", "downloads-http.conf"]:
            (stage / name).write_text("# test Apache include\n")

        def fetch(name):
            if fail:
                raise RuntimeError("test health check failure")
            return (site / (name or "index.html")).read_bytes()

        with patch.multiple(installer, BASE=self.base, **paths), patch.object(installer.os, "geteuid", return_value=0), \
                patch("sys.argv", ["installer", str(stage)]), patch.object(installer, "run"), \
                patch.object(installer, "fetch", side_effect=fetch), redirect_stdout(io.StringIO()):
            installer.main()

    def test_successful_activation_deduplicates_apk_and_prunes(self):
        self.activate()
        active = (self.base / "deployment").resolve()
        apk = active / "textpad-latest.apk"
        versioned = next((active / "downloads").glob("*.apk"))
        self.assertEqual(apk.stat().st_ino, versioned.stat().st_ino)
        self.assertEqual(len(list(self.releases.iterdir())), 3)
        self.assertTrue(self.old[-1].exists())

    def test_failed_activation_rolls_back_without_pruning(self):
        with self.assertRaisesRegex(RuntimeError, "health check"):
            self.activate(fail=True)
        self.assertEqual((self.base / "deployment").resolve(), self.old[-1])
        for release in self.old:
            self.assertTrue(release.exists())


if __name__ == "__main__":
    unittest.main()
