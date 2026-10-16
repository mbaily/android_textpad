#!/usr/bin/env python3
"""Install a prepared download site on nucbox3. Invoked through passwordless sudo."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile

BASE = Path("/opt/android_textpad")
HTTPS_VHOST = Path("/etc/apache2/sites-available/view_git.conf")
HTTP_VHOST = Path("/etc/apache2/sites-available/000-default.conf")
HTTPS_INCLUDE = Path("/etc/apache2/android-textpad-downloads.conf")
HTTP_INCLUDE = Path("/etc/apache2/android-textpad-http.conf")
CA = "/etc/ssl/nucbox3/nucbox3-ca/nucbox3.crt"
URL = "https://git23069.hopto.org/android_textpad/"


def run(*args):
    return subprocess.run(args, check=True, capture_output=True).stdout


def atomic_write(path, data, mode=0o644):
    descriptor, name = tempfile.mkstemp(prefix=".textpad-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(name, mode)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def include_in_vhost(original, include):
    text = original.decode()
    directive = f"IncludeOptional {include}"
    if any(line.strip() == directive for line in text.splitlines()):
        return original
    endings = list(re.finditer(r"</VirtualHost\s*>", text, re.IGNORECASE))
    if len(endings) != 1:
        raise RuntimeError("Expected exactly one VirtualHost in the existing configuration")
    position = endings[0].start()
    return (text[:position] + f"    {directive}\n" + text[position:]).encode()


def switch(target):
    temporary = BASE / ".deployment-next"
    if temporary.exists() or temporary.is_symlink():
        raise RuntimeError("Another Textpad deployment may be in progress")
    temporary.symlink_to(target)
    try:
        os.replace(temporary, BASE / "deployment")
    finally:
        temporary.unlink(missing_ok=True)


def fetch(path):
    return run("curl", "--silent", "--show-error", "--fail", "--max-time", "15",
               "--cacert", CA, "--resolve", "git23069.hopto.org:443:127.0.0.1", URL + path)


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise RuntimeError("Run with sudo and one prepared staging-directory argument")
    stage = Path(sys.argv[1]).resolve()
    site = stage / "site"
    metadata = json.loads((site / "release.json").read_text())
    filename = metadata["filename"]
    if not re.fullmatch(r"textpad-[0-9A-Za-z._-]+-debug-[0-9a-f]{12}\.apk", filename):
        raise RuntimeError("Unexpected APK filename")
    expected_files = {"index.html", "site.css", "release.json", "textpad-latest.apk",
                      "textpad-latest.apk.sha256", "downloads/" + filename,
                      "downloads/" + filename + ".sha256"}
    actual_files = set()
    for path in site.rglob("*"):
        if path.is_symlink():
            raise RuntimeError("The site package must not contain symlinks")
        if path.is_file():
            actual_files.add(path.relative_to(site).as_posix())
    if actual_files != expected_files:
        raise RuntimeError("Unexpected or missing files in the site package")
    for path in [site / "downloads" / filename, site / "textpad-latest.apk"]:
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != metadata["sha256"] or len(data) != metadata["size_bytes"]:
            raise RuntimeError("APK checksum or size does not match release metadata")
    run("apache2ctl", "configtest")
    BASE.mkdir(mode=0o755, exist_ok=True)
    current = BASE / "deployment"
    if current.exists() and not current.is_symlink():
        raise RuntimeError("Refusing to replace an existing deployment directory that is not a release symlink")
    old_target = os.readlink(current) if current.is_symlink() else None
    paths = [HTTPS_VHOST, HTTP_VHOST, HTTPS_INCLUDE, HTTP_INCLUDE]
    originals = {path: path.read_bytes() if path.exists() else None for path in paths}
    modes = {path: stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644 for path in paths}
    if originals[HTTPS_VHOST] is None or originals[HTTP_VHOST] is None:
        raise RuntimeError("The expected existing Apache virtual hosts are missing")
    release_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + metadata["sha256"][:12]
    releases = BASE / "releases"
    releases.mkdir(mode=0o755, exist_ok=True)
    release = releases / release_id
    shutil.copytree(site, release)
    for path in [release, *release.rglob("*")]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    backups = BASE / "config-backups"
    backups.mkdir(mode=0o700, exist_ok=True)
    backup = backups / release_id
    backup.mkdir(mode=0o700)
    for path, data in originals.items():
        if data is not None:
            atomic_write(backup / path.name, data, 0o600)
    atomic_write(backup / "previous-deployment.json", json.dumps({"target": old_target}).encode(), 0o600)
    try:
        atomic_write(HTTPS_INCLUDE, (stage / "downloads.conf").read_bytes())
        atomic_write(HTTP_INCLUDE, (stage / "downloads-http.conf").read_bytes())
        atomic_write(HTTPS_VHOST, include_in_vhost(originals[HTTPS_VHOST], HTTPS_INCLUDE), modes[HTTPS_VHOST])
        atomic_write(HTTP_VHOST, include_in_vhost(originals[HTTP_VHOST], HTTP_INCLUDE), modes[HTTP_VHOST])
        switch("releases/" + release_id)
        run("apache2ctl", "configtest")
        run("systemctl", "reload", "apache2")
        run("systemctl", "is-active", "--quiet", "apache2")
        served = json.loads(fetch("release.json"))
        if served != metadata or hashlib.sha256(fetch("textpad-latest.apk")).hexdigest() != metadata["sha256"]:
            raise RuntimeError("The served release does not match the installed APK")
        page = fetch("")
        if filename.encode() not in page or b"@@VERSION@@" in page:
            raise RuntimeError("The download page does not match the release")
    except BaseException:
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, data, modes[path])
        if old_target is None:
            current.unlink(missing_ok=True)
        else:
            switch(old_target)
        run("apache2ctl", "configtest")
        run("systemctl", "reload", "apache2")
        raise
    print(json.dumps({"url": URL, "apk": URL + "textpad-latest.apk", "version": metadata["version"],
                      "sha256": metadata["sha256"], "deployment": str(current), "release": str(release),
                      "configuration_backup": str(backup)}, indent=2))


if __name__ == "__main__":
    # Serialize updates and rollback using a host-local advisory lock.
    import fcntl
    try:
        with open("/run/lock/android-textpad-downloads.lock", "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            main()
    except subprocess.CalledProcessError as error:
        sys.stderr.write(error.stderr.decode(errors="replace"))
        raise SystemExit(f"Command failed: {error.cmd[0]} (exit {error.returncode})")
