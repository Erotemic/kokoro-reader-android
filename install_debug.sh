#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    ./build_debug.sh
fi

if ! command -v adb >/dev/null 2>&1; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        ADB="$HOME/Android/Sdk/platform-tools/adb"
    else
        echo "ERROR: adb not found. Install Android platform-tools or add adb to PATH." >&2
        exit 1
    fi
else
    ADB="adb"
fi

"$ADB" install -r "$APK"
