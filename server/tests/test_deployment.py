"""Exercise release cleanup and APK activation without touching the host."""
from contextlib import closing, redirect_stdout
import hashlib
from importlib.util import module_from_spec, spec_from_file_location
import io
import json
import os
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = spec_from_file_location("downloads", Path(__file__).parents[1] / "deploy/install-downloads.py")
installer = module_from_spec(spec)
spec.loader.exec_module(installer)
server_spec = spec_from_file_location("server_installer", Path(__file__).parents[1] / "deploy/install-server.py")
server_installer = module_from_spec(server_spec)
server_spec.loader.exec_module(server_installer)


class DeploymentTests(unittest.TestCase):
    def test_external_database_is_backed_up_without_recreating_default_data(self):
        stage = Path(self.temp.name)/'stage'
        (stage/'app').mkdir(parents=True)
        (stage/'app/requirements.lock').write_text('')
        for name in ['server.conf', 'android-textpad.service', 'android-textpad-backup.service', 'android-textpad-backup.timer']:
            (stage/name).write_text('# fixture\n')
        config = Path(self.temp.name)/'config'
        config.mkdir()
        database = Path(self.temp.name)/'user-data/custom.sqlite3'
        database.parent.mkdir()
        with closing(sqlite3.connect(database)) as db:
            db.execute('CREATE TABLE precious (content TEXT)')
            db.execute("INSERT INTO precious VALUES ('preserved data')")
            db.commit()
        (config/'server.env').write_text(f'TEXTPAD_DATABASE={database}\n')
        units = Path(self.temp.name)/'units'
        units.mkdir()
        vhost = Path(self.temp.name)/'vhost.conf'
        vhost.write_bytes(b'<VirtualHost *:443>\n</VirtualHost>\n')
        default_data = self.base/'data'
        with patch.multiple(server_installer, BASE=self.base, CONFIG=config, DATA=default_data,
                LEGACY_DATA=Path(self.temp.name)/'missing-legacy', UNIT_DIRECTORY=units,
                APACHE_INCLUDE=Path(self.temp.name)/'server.conf', HTTPS_VHOST=vhost), \
                patch.object(server_installer, 'helpers', return_value=(installer.atomic_write, lambda data,path:data, lambda *args,**kwargs:[])), \
                patch.object(server_installer, 'run', return_value=b'Sign in'), \
                patch.object(server_installer.subprocess, 'run', return_value=SimpleNamespace(returncode=0)), \
                patch.object(server_installer.os, 'chown'), \
                patch.object(server_installer.urllib.request, 'urlopen', return_value=io.BytesIO(b'{"status":"ok"}')), \
                redirect_stdout(io.StringIO()):
            server_installer.main(stage)
        self.assertFalse(default_data.exists())
        saved = list((database.parent/'backups').glob('before-deploy-*.sqlite3'))
        self.assertEqual(len(saved), 1)
        with closing(sqlite3.connect(saved[0])) as restored:
            self.assertEqual(restored.execute('PRAGMA integrity_check').fetchall(), [('ok',)])
            self.assertEqual(restored.execute('SELECT content FROM precious').fetchall(), [('preserved data',)])

    def test_server_deployment_uses_configured_database_filename_and_backup_parent(self):
        with tempfile.TemporaryDirectory() as temporary:
            env = Path(temporary) / "server.env"
            env.write_text("# Preserved configuration\nTEXTPAD_DATABASE=/srv/textpad-data/custom.sqlite3\n")
            database, backups = server_installer.storage_paths(server_installer.read_environment(env))
        self.assertEqual(database, Path('/srv/textpad-data/custom.sqlite3'))
        self.assertEqual(backups, database.parent/'backups')

    def test_server_deployment_keeps_default_and_legacy_database_migration(self):
        expected = server_installer.DATA/'textpad.sqlite3'
        for env in ({}, {'TEXTPAD_DATABASE':str(server_installer.LEGACY_DATA/'textpad.sqlite3')}):
            self.assertEqual(server_installer.storage_paths(env), (expected, expected.parent/'backups'))
        with self.assertRaisesRegex(RuntimeError, 'absolute path'):
            server_installer.storage_paths({'TEXTPAD_DATABASE':'relative.sqlite3'})

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
