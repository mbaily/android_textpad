#!/usr/bin/env bash
set -euo pipefail
textpad_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
textpad_host="${TEXTPAD_DEPLOY_HOST:-$(cat "$textpad_root/.deploy-host" 2>/dev/null || true)}"
if [[ -z "$textpad_host" ]]; then
    printf '%s\n' 'Set TEXTPAD_DEPLOY_HOST or write user@host to .deploy-host' >&2
    exit 1
fi
python3 "$textpad_root/scripts/package-downloads.py" "$@"
textpad_stage="$(ssh -o BatchMode=yes -o ConnectTimeout=15 "$textpad_host" 'mktemp -d /tmp/textpad-downloads.XXXXXXXX')"
if [[ ! "$textpad_stage" =~ ^/tmp/textpad-downloads\.[a-zA-Z0-9]+$ ]]; then
    printf '%s\n' 'Unexpected remote staging directory' >&2
    exit 1
fi
cleanup() { ssh -o BatchMode=yes "$textpad_host" "rm -rf -- '$textpad_stage'" || true; }
trap cleanup EXIT
scp -q -o BatchMode=yes "$textpad_root/server/deploy/install-downloads.py" \
    "$textpad_root/server/apache/downloads.conf" "$textpad_root/server/apache/downloads-http.conf" \
    "$textpad_host:$textpad_stage/"
rsync -a --chmod=Du=rwx,Dgo=rx,Fu=rw,Fgo=r -e 'ssh -o BatchMode=yes' \
    "$textpad_root/deployment/" "$textpad_host:$textpad_stage/site/"
ssh -o BatchMode=yes "$textpad_host" "sudo -n python3 '$textpad_stage/install-downloads.py' '$textpad_stage'"
