"""Cover the public HTTPS subpath while the WSGI app receives plain HTTP."""
from importlib.util import module_from_spec, spec_from_file_location
from contextlib import closing, redirect_stdout
import io
import os
from pathlib import Path
import re
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from werkzeug.middleware.dispatcher import DispatcherMiddleware
from werkzeug.wrappers import Response
from textpad_server import create_app

spec = spec_from_file_location("caddy_installer", Path(__file__).parents[1] / "deploy/install-server.py")
installer = module_from_spec(spec)
spec.loader.exec_module(installer)
download_spec = spec_from_file_location("caddy_download_installer", Path(__file__).parents[1] / "deploy/install-downloads.py")
downloads = module_from_spec(download_spec)
download_spec.loader.exec_module(downloads)


class CaddyDeploymentTests(unittest.TestCase):
    def test_activation_and_failed_public_check_preserve_other_routes_and_state(self):
        for fail in [False, True]:
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                base, config, data, stage, units = [root / name for name in ['base', 'config', 'data', 'stage', 'units']]
                for directory in [base, config, data, stage / 'app', units]:
                    directory.mkdir(parents=True)
                (base / 'config-backups').mkdir()
                (stage / 'app/requirements.lock').write_text('')
                for name in ['android-textpad.service', 'android-textpad-backup.service', 'android-textpad-backup.timer']:
                    (stage / name).write_text('# new unit\n')
                    (units / name).write_text('# old unit\n')
                (stage / 'server.conf').write_text('# compatibility API\n')
                (stage / 'textpad.caddy').write_text('handle_path /android_textpad/* { respond app }\n')
                database = data / 'textpad.sqlite3'
                with closing(sqlite3.connect(database)) as db:
                    db.execute('CREATE TABLE precious (content TEXT)')
                    db.execute("INSERT INTO precious VALUES ('retained')")
                    db.commit()
                old_env = f'TEXTPAD_DATABASE={database}\nTEXTPAD_PUBLIC_URL=https://old.example/android_textpad\n'
                (config / 'server.env').write_text(old_env)
                caddy, vhost, apache = root / 'Caddyfile', root / 'vhost', root / 'apache'
                before = b'https://example.test:9443 {\n\t# Only explicitly configured app paths are published.\n\thandle { respond missing 404 }\n}\n'
                caddy.write_bytes(before)
                vhost.write_text('<VirtualHost *:443>\n</VirtualHost>\n')
                apache.write_text('# old proxy\n')

                def command(*args, **kwargs):
                    if fail and args[0] == 'curl':
                        raise RuntimeError('Public HTTPS check failed')
                    return b'Sign in'

                with patch.multiple(installer, BASE=base, CONFIG=config, DATA=data,
                        LEGACY_DATA=root / 'missing', UNIT_DIRECTORY=units, CADDY_CONFIG=caddy,
                        HTTPS_VHOST=vhost, APACHE_INCLUDE=apache), \
                        patch.object(installer, 'helpers', return_value=(downloads.atomic_write, downloads.include_in_vhost, lambda *args, **kwargs: [])), \
                        patch.object(installer, 'run', side_effect=command) as run, \
                        patch.object(installer.subprocess, 'run', return_value=SimpleNamespace(returncode=0)), \
                        patch.object(installer.pwd, 'getpwnam', return_value=SimpleNamespace(pw_uid=os.getuid())), \
                        patch.object(installer.grp, 'getgrnam', return_value=SimpleNamespace(gr_gid=os.getgid())), \
                        patch.object(installer.os, 'chown'), \
                        patch.object(installer.urllib.request, 'urlopen', return_value=io.BytesIO(b'{"status":"ok"}')), \
                        redirect_stdout(io.StringIO()):
                    if fail:
                        with self.assertRaisesRegex(RuntimeError, 'Public HTTPS check failed'):
                            installer.main(stage)
                    else:
                        installer.main(stage)
                self.assertIn(('systemctl', 'reload', 'caddy'), [call.args for call in run.call_args_list])
                if fail:
                    self.assertEqual(before, caddy.read_bytes())
                    self.assertEqual(old_env, (config / 'server.env').read_text())
                    self.assertEqual(0o640, (config / 'server.env').stat().st_mode & 0o777)
                    self.assertEqual('# old proxy\n', apache.read_text())
                    self.assertFalse((base / 'server').exists())
                else:
                    self.assertIn(b'BEGIN android_textpad', caddy.read_bytes())
                    self.assertIn(installer.PUBLIC, (config / 'server.env').read_text())
                    self.assertTrue((base / 'server').is_symlink())
                with closing(sqlite3.connect(database)) as db:
                    self.assertEqual([('retained',)], db.execute('SELECT content FROM precious').fetchall())


class CaddyConfigurationTests(unittest.TestCase):
    def test_routes_preserve_other_apps_and_replace_only_their_own_block(self):
        original = b"https://example.test:9443 {\n\thandle_path /other/* { respond ok }\n\t# Only explicitly configured app paths are published.\n\thandle { respond missing 404 }\n}\n"
        configured = installer.caddy_routes(original, b"handle_path /android_textpad/* { respond first }\n")
        replaced = installer.caddy_routes(configured, b"handle_path /android_textpad/* { respond second }\n")
        self.assertNotIn(b"respond first", replaced)
        self.assertIn(b"respond second", replaced)
        self.assertIn(b"handle_path /other/* { respond ok }", replaced)
        self.assertEqual(configured, installer.caddy_routes(configured, b"handle_path /android_textpad/* { respond first }\n"))

    def test_ambiguous_caddy_configuration_is_refused(self):
        for contents in [b"no known site", b"\t# BEGIN android_textpad\n", b"\t# END android_textpad\n"]:
            with self.subTest(contents=contents), self.assertRaises(RuntimeError):
                installer.caddy_routes(contents, b"respond ok")

    def test_public_url_update_retains_database_and_credentials(self):
        original = b"TEXTPAD_DATABASE=/external/textpad.sqlite3\nTEXTPAD_TOKEN_FILE=/external/token\nTEXTPAD_PUBLIC_URL=https://old.example/android_textpad\n"
        result = installer.public_environment(original)
        self.assertIn(b"TEXTPAD_DATABASE=/external/textpad.sqlite3\n", result)
        self.assertIn(b"TEXTPAD_TOKEN_FILE=/external/token\n", result)
        self.assertIn(installer.PUBLIC.encode(), result)
        self.assertEqual(result, installer.public_environment(result))


class CaddySubpathTests(unittest.TestCase):
    prefix = "/android_textpad"
    origin = "https://git23069.hopto.org:9443"

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.token = "caddy-test-api-token-at-least-32-characters"
        self.app = create_app({
            "TESTING": True, "DATABASE": str(Path(self.folder.name) / "db.sqlite3"),
            "API_TOKEN": self.token, "SECRET_KEY": "caddy-test-session-secret-32-characters",
            "PUBLIC_URL": self.origin + self.prefix,
        })
        self.addCleanup(self.app.extensions["changes"].close)
        self.app.wsgi_app = DispatcherMiddleware(Response(status=404), {self.prefix: self.app.wsgi_app})
        self.client = self.app.test_client()

    def test_login_assets_cookie_connection_and_csrf_use_the_new_subpath_and_origin(self):
        page = self.client.get(self.prefix + "/editor/login", base_url=self.origin)
        self.assertEqual(200, page.status_code)
        self.assertIn(b'/android_textpad/editor/static/editor.css', page.data)
        csrf = re.search(rb'name="csrf" value="([a-f0-9]+)"', page.data).group(1).decode()
        response = self.client.post(self.prefix + "/editor/login", base_url=self.origin,
            data={"csrf": csrf, "credential": self.token}, headers={"Origin": self.origin})
        self.assertEqual(303, response.status_code)
        self.assertEqual(self.prefix + "/editor/", response.headers["Location"])
        cookie = response.headers["Set-Cookie"]
        for setting in ["Secure", "HttpOnly", "Path=/android_textpad/editor", "SameSite=Strict"]:
            self.assertIn(setting, cookie)
        connection = self.client.get(self.prefix + "/editor/api/connection", base_url=self.origin)
        self.assertEqual(self.origin + self.prefix, connection.json["endpoint"])
        refused = self.client.put(self.prefix + "/editor/api/documents/test", base_url=self.origin,
            json={}, headers={"Origin": "https://git23069.hopto.org", "X-CSRF-Token": csrf})
        self.assertEqual(403, refused.status_code)
        manifest = self.client.get(self.prefix + "/editor/manifest.webmanifest", base_url=self.origin)
        self.assertEqual(self.prefix + "/editor/", manifest.json["start_url"])
        with self.client.get(self.prefix + "/editor/static/editor.css", base_url=self.origin) as stylesheet:
            self.assertEqual(200, stylesheet.status_code)
        self.assertEqual(200, self.client.get(self.prefix + "/api/v1/documents", base_url=self.origin,
            headers={"Authorization": "Bearer " + self.token}).status_code)
