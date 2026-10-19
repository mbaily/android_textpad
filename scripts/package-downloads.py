#!/usr/bin/env python3
"""Package the current signed Android build into the static deployment directory."""
import argparse
from datetime import datetime, timezone
import hashlib
import html
import json
from pathlib import Path
import re
import shutil
import os
import subprocess
import tempfile
import zipfile


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=root / "app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    apk = args.apk.resolve()
    if not apk.is_file():
        parser.error("APK is missing. Run ./scripts/build.sh first.")
    metadata_path = apk.parent / "output-metadata.json"
    metadata = json.loads(metadata_path.read_text())
    builds = [entry for entry in metadata["elements"] if entry["outputFile"] == apk.name]
    if len(builds) != 1 or metadata.get("variantName") != "debug":
        parser.error("Expected one debug APK matching its Android build metadata.")
    version = builds[0]["versionName"]
    if not re.fullmatch(r"[0-9A-Za-z._-]+", version):
        parser.error("The version name is not safe for a download filename.")
    signer = shutil.which("apksigner")
    if signer is None:
        sdk_paths = [Path(value) for value in [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")] if value]
        sdk_paths += [Path.home() / "Android/Sdk", Path.home() / ".cache/android-textpad/sdk"]
        for sdk in sdk_paths:
            candidate = sdk / "build-tools/37.0.0/apksigner"
            if candidate.is_file():
                signer = str(candidate)
                break
    if signer is None:
        parser.error("apksigner is missing. Set ANDROID_HOME to the SDK used to build this APK.")
    subprocess.run([signer, "verify", str(apk)], check=True)
    with zipfile.ZipFile(apk) as archive:
        if not {"AndroidManifest.xml", "classes.dex"}.issubset(archive.namelist()) or archive.testzip():
            parser.error("The APK is not a valid Android package.")
    data = apk.read_bytes()
    sha256 = hashlib.sha256(data).hexdigest()
    filename = f"textpad-{version}-debug-{sha256[:12]}.apk"
    release = {
        "application_id": metadata["applicationId"],
        "version": version,
        "version_code": builds[0]["versionCode"],
        "minimum_android": "16",
        "minimum_sdk": metadata["minSdkVersionForDexing"],
        "build_type": "debug",
        "filename": filename,
        "size_bytes": len(data),
        "sha256": sha256,
        "apk_built_at": datetime.fromtimestamp(apk.stat().st_mtime, timezone.utc).isoformat(),
    }
    replacements = {"VERSION": version, "APK_FILENAME": filename,
                    "APK_SIZE": f"{len(data) / 1024:,.0f} KiB", "SHA256": sha256}
    template = (root / "server/site/index.html").read_text()
    for key, value in replacements.items():
        template = template.replace(f"@@{key}@@", html.escape(value, quote=True))
    if re.search(r"@@[A-Z_]+@@", template):
        raise RuntimeError("Unresolved page template values")
    # Prepare completely before replacing the previous local package.
    with tempfile.TemporaryDirectory(prefix="textpad-downloads-") as temporary:
        staged = Path(temporary) / "site"
        (staged / "downloads").mkdir(parents=True)
        (staged / "index.html").write_text(template)
        shutil.copyfile(root / "server/site/site.css", staged / "site.css")
        shutil.copyfile(apk, staged / "downloads" / filename)
        shutil.copyfile(apk, staged / "textpad-latest.apk")
        (staged / "downloads" / (filename + ".sha256")).write_text(f"{sha256}  {filename}\n")
        (staged / "textpad-latest.apk.sha256").write_text(f"{sha256}  textpad-latest.apk\n")
        (staged / "release.json").write_text(json.dumps(release, indent=2) + "\n")
        output = root / "deployment"
        if output.is_symlink() or (output.exists() and not (output / "release.json").is_file()):
            raise RuntimeError("Refusing to replace an unrecognized deployment directory")
        if output.exists():
            shutil.rmtree(output)
        shutil.copytree(staged, output)
    print(f"Packaged Textpad {version}: {len(data):,} bytes")
    print(f"SHA-256: {sha256}")
    print(f"Static site: {output}")


if __name__ == "__main__":
    main()
