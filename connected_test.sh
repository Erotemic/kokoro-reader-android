#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ "${KOKORO_ALLOW_DESTRUCTIVE_CONNECTED_TESTS:-}" != "1" ]; then
    cat >&2 <<'MESSAGE'
ERROR: Connected tests intentionally stop playback and clear Kokoro Reader debug-app
text, history, and audio cache. Run them only on a disposable emulator or test device.

Re-run with:
  KOKORO_ALLOW_DESTRUCTIVE_CONNECTED_TESTS=1 ./connected_test.sh
MESSAGE
    exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        export PATH="$ANDROID_HOME/platform-tools:$PATH"
    elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
    else
        echo "ERROR: adb not found. Start an emulator or connect a device with Android platform-tools installed." >&2
        exit 1
    fi
fi

if ! adb get-state >/dev/null 2>&1; then
    echo "ERROR: No authorized Android emulator/device is connected." >&2
    exit 1
fi

./tools/run_gradle.sh connectedDebugAndroidTest

echo
echo "Connected-test reports: app/build/reports/androidTests/connected/debug/index.html"
