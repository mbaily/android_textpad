#!/usr/bin/env python3
"""Deploy the sync service after local checks; preserve credentials and SQLite data."""
from contextlib import closing
from datetime import datetime, timezone
import fcntl
import grp
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.request

from importlib.util import spec_from_file_location, module_from_spec

sys.dont_write_bytecode = True

BASE = Path("/opt/android_textpad")
CONFIG = Path("/etc/android-textpad")
DATA = Path("/var/lib/android-textpad")
UNIT_DIRECTORY = Path("/etc/systemd/system")
APACHE_INCLUDE = Path("/etc/apache2/android-textpad-server.conf")
HTTPS_VHOST = Path("/etc/apache2/sites-available/view_git.conf")
PUBLIC = "https://git23069.hopto.org/android_textpad"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs).stdout


def helpers(stage):
    spec = spec_from_file_location("download_installer", stage / "install-downloads.py")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.atomic_write, module.include_in_vhost


def link(target):
    temporary = BASE / ".server-next"
    if temporary.exists() or temporary.is_symlink():
        raise RuntimeError("Another server deployment may be in progress")
    temporary.symlink_to(target)
    try:
        os.replace(temporary, BASE / "server")
    finally:
        temporary.unlink(missing_ok=True)


def main(stage):
    atomic_write, include_in_vhost = helpers(stage)
    run("apache2ctl", "configtest")
    current = BASE / "server"
    if current.exists() and not current.is_symlink():
        raise RuntimeError("Refusing to replace an unrecognized server directory")
    old_target = os.readlink(current) if current.is_symlink() else None
    try:
        group = grp.getgrnam("textpad")
    except KeyError:
        run("/usr/sbin/groupadd", "--system", "textpad")
        group = grp.getgrnam("textpad")
    try:
        account = pwd.getpwnam("textpad")
    except KeyError:
        run("/usr/sbin/useradd", "--system", "--gid", "textpad", "--home-dir", "/nonexistent",
            "--no-create-home", "--shell", "/usr/sbin/nologin", "textpad")
        account = pwd.getpwnam("textpad")
    DATA.mkdir(mode=0o700, exist_ok=True)
    os.chown(DATA, account.pw_uid, group.gr_gid)
    CONFIG.mkdir(mode=0o750, exist_ok=True)
    os.chown(CONFIG, 0, group.gr_gid)
    for name in ["api-token", "session-key"]:
        path = CONFIG / name
        if not path.exists():
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
            with os.fdopen(descriptor, "w") as output:
                output.write(secrets.token_urlsafe(48) + "\n")
                output.flush()
                os.fsync(output.fileno())
            os.chown(path, 0, group.gr_gid)
    environment_path = CONFIG / "server.env"
    if not environment_path.exists():
        atomic_write(environment_path, (
            f"TEXTPAD_DATABASE={DATA}/textpad.sqlite3\n"
            f"TEXTPAD_TOKEN_FILE={CONFIG}/api-token\n"
            f"TEXTPAD_SESSION_FILE={CONFIG}/session-key\n"
            f"TEXTPAD_PUBLIC_URL={PUBLIC}\n").encode(), 0o640)
        os.chown(environment_path, 0, group.gr_gid)
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    for line in environment_path.read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            environment[key] = value
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    releases = BASE / "server-releases"
    releases.mkdir(mode=0o755, exist_ok=True)
    release = releases / stamp
    shutil.copytree(stage / "app", release)
    for path in [release, *release.rglob("*")]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    run("/usr/bin/python3", "-m", "venv", str(release / ".venv"))
    run(str(release / ".venv/bin/pip"), "install", "--no-index", "--find-links", str(stage / "wheels"),
        "--requirement", str(release / "requirements.lock"))
    run("/usr/sbin/runuser", "-u", "textpad", "--", str(release / ".venv/bin/python"),
        "-m", "textpad_server", "init", cwd=release, env=environment)
    run("/usr/sbin/runuser", "-u", "textpad", "--", str(release / ".venv/bin/gunicorn"),
        "--config", "python:textpad_server.gunicorn_config", "--check-config", "textpad_server:create_app()", cwd=release, env=environment)
    backups = DATA / "backups"
    backups.mkdir(mode=0o700, exist_ok=True)
    os.chown(backups, account.pw_uid, group.gr_gid)
    database = DATA / "textpad.sqlite3"
    snapshot = backups / f"before-deploy-{stamp}.sqlite3"
    with closing(sqlite3.connect(database)) as source, closing(sqlite3.connect(snapshot)) as destination:
        source.backup(destination)
    snapshot.chmod(0o600)
    os.chown(snapshot, account.pw_uid, group.gr_gid)
    units = ["android-textpad.service", "android-textpad-backup.service", "android-textpad-backup.timer"]
    paths = [APACHE_INCLUDE, HTTPS_VHOST, *[UNIT_DIRECTORY / name for name in units]]
    originals = {path: path.read_bytes() if path.exists() else None for path in paths}
    config_backup = BASE / "config-backups" / ("server-" + stamp)
    config_backup.mkdir(mode=0o700)
    for path, contents in originals.items():
        if contents is not None:
            atomic_write(config_backup / path.name, contents, 0o600)
    atomic_write(config_backup / "previous-server.json", json.dumps({"target":old_target}).encode(), 0o600)
    was_active = subprocess.run(["systemctl", "is-active", "--quiet", "android-textpad.service"], capture_output=True).returncode == 0
    try:
        for name in units:
            atomic_write(UNIT_DIRECTORY / name, (stage / name).read_bytes())
        link("server-releases/" + stamp)
        run("systemctl", "daemon-reload")
        run("systemctl", "restart", "android-textpad.service")
        for attempt in range(30):
            try:
                with urllib.request.urlopen("http://127.0.0.1:8768/health", timeout=2) as response:
                    if json.load(response).get("status") == "ok":
                        break
            except (OSError, ValueError):
                time.sleep(0.5)
        else:
            raise RuntimeError("The service did not become healthy")
        atomic_write(APACHE_INCLUDE, (stage / "server.conf").read_bytes())
        atomic_write(HTTPS_VHOST, include_in_vhost(originals[HTTPS_VHOST], APACHE_INCLUDE))
        run("apache2ctl", "configtest")
        run("systemctl", "reload", "apache2")
        page = run("curl", "--silent", "--show-error", "--fail", "--max-time", "15",
                   "--cacert", "/etc/ssl/nucbox3/nucbox3-ca/nucbox3.crt", "--resolve", "git23069.hopto.org:443:127.0.0.1",
                   PUBLIC + "/editor/login")
        if b"Sign in" not in page:
            raise RuntimeError("The HTTPS editor did not return its sign-in page")
        run("systemctl", "enable", "android-textpad.service")
        run("systemctl", "enable", "--now", "android-textpad-backup.timer")
    except BaseException:
        for path, contents in originals.items():
            if contents is None:
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, contents)
        if old_target is None:
            subprocess.run(["systemctl", "stop", "android-textpad.service"], capture_output=True)
            current.unlink(missing_ok=True)
        else:
            link(old_target)
        run("systemctl", "daemon-reload")
        if was_active:
            run("systemctl", "restart", "android-textpad.service")
        run("apache2ctl", "configtest")
        run("systemctl", "reload", "apache2")
        raise
    print(json.dumps({"editor":PUBLIC+"/editor/", "sync_endpoint":PUBLIC,
                      "service":"android-textpad.service", "database":str(database),
                      "credential_file":str(CONFIG/"api-token"),"release":str(release),
                      "database_backup":str(snapshot)}, indent=2))


if __name__ == "__main__":
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise SystemExit("Run with sudo and a prepared staging directory")
    try:
        with open("/run/lock/android-textpad-downloads.lock", "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            main(Path(sys.argv[1]).resolve())
    except subprocess.CalledProcessError as error:
        sys.stderr.write(error.stderr.decode(errors="replace"))
        raise SystemExit(f"Command failed: {error.cmd[0]} (exit {error.returncode})")
