#!/usr/bin/env bash
set -euo pipefail
textpad_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.cache/android-textpad/jdk/bin/javac" ]]; then
    export JAVA_HOME="$HOME/.cache/android-textpad/jdk"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.cache/android-textpad/sdk" ]]; then
    export ANDROID_HOME="$HOME/.cache/android-textpad/sdk"
fi
cd "$textpad_root"
if [[ $# -eq 0 ]]; then
    set -- :core:test :app:lintDebug :app:assembleDebug
fi
exec ./gradlew "$@" --console=plain
