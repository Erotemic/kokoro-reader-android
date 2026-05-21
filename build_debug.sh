#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java is not installed. Install JDK 17+ first." >&2
    exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
fi

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "WARNING: ANDROID_HOME is not set. Gradle may still find the SDK if Android Studio configured it." >&2
fi

if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
else
    GRADLE_VERSION="8.10.2"
    LOCAL_DIR=".gradle-local"
    GRADLE_HOME="$LOCAL_DIR/gradle-$GRADLE_VERSION"
    ZIP_FILE="$LOCAL_DIR/gradle-$GRADLE_VERSION-bin.zip"
    mkdir -p "$LOCAL_DIR"
    if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
        echo "Gradle not found. Downloading Gradle $GRADLE_VERSION..."
        if command -v curl >/dev/null 2>&1; then
            curl -L -o "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
        elif command -v wget >/dev/null 2>&1; then
            wget -O "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
        else
            echo "ERROR: Install gradle, curl, or wget." >&2
            exit 1
        fi
        unzip -q -o "$ZIP_FILE" -d "$LOCAL_DIR"
    fi
    GRADLE_CMD="$GRADLE_HOME/bin/gradle"
fi

"$GRADLE_CMD" assembleDebug

echo
echo "Built: app/build/outputs/apk/debug/app-debug.apk"
