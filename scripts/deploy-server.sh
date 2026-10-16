#!/usr/bin/env bash
set -euo pipefail
textpad_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
textpad_host="<user>@<your-server>"
textpad_python="${TEXTPAD_BUILD_PYTHON:-$HOME/.cache/android-textpad/server-venv/bin/python}"
if [[ ! -x "$textpad_python" ]]; then
    printf '%s\n' 'Create a Python 3.13 virtual environment and install server/requirements.lock, then set TEXTPAD_BUILD_PYTHON.' >&2
    exit 1
fi
cd "$textpad_root"
npm ci --prefix server/frontend --no-audit --no-fund
npm run build --prefix server/frontend
PYTHONPATH=server "$textpad_python" -m unittest discover -s server/tests -v
mkdir -p .server-package/wheels
"$textpad_python" -m pip download --only-binary=:all: --requirement server/requirements.lock --dest .server-package/wheels
textpad_stage="$(ssh -o BatchMode=yes -o ConnectTimeout=15 "$textpad_host" 'mktemp -d /tmp/textpad-server.XXXXXXXX')"
if [[ ! "$textpad_stage" =~ ^/tmp/textpad-server\.[a-zA-Z0-9]+$ ]]; then exit 1; fi
cleanup() { ssh -o BatchMode=yes "$textpad_host" "rm -rf -- '$textpad_stage'" || true; }
trap cleanup EXIT
scp -q -o BatchMode=yes server/deploy/install-server.py server/deploy/install-downloads.py server/apache/server.conf \
    server/deploy/android-textpad.service server/deploy/android-textpad-backup.service server/deploy/android-textpad-backup.timer \
    "$textpad_host:$textpad_stage/"
rsync -a --exclude=__pycache__/ --chmod=Du=rwx,Dgo=rx,Fu=rw,Fgo=r -e 'ssh -o BatchMode=yes' \
    server/textpad_server "$textpad_host:$textpad_stage/app/"
scp -q -o BatchMode=yes server/requirements.lock "$textpad_host:$textpad_stage/app/requirements.lock"
rsync -a -e 'ssh -o BatchMode=yes' .server-package/wheels/ "$textpad_host:$textpad_stage/wheels/"
ssh -o BatchMode=yes "$textpad_host" "sudo -n python3 -B '$textpad_stage/install-server.py' '$textpad_stage'"
