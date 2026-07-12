#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

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

download_file() {
    local url="$1"
    local destination="$2"
    local partial="${destination}.part"
    rm -f "$partial"
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --retry-delay 2 --output "$partial" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --output-document="$partial" "$url"
    else
        echo "ERROR: Install gradle, curl, or wget." >&2
        exit 1
    fi
    mv -f "$partial" "$destination"
}

if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD=(gradle)
else
    GRADLE_VERSION="8.10.2"
    LOCAL_DIR=".gradle-local"
    GRADLE_HOME="$LOCAL_DIR/gradle-$GRADLE_VERSION"
    ZIP_FILE="$LOCAL_DIR/gradle-$GRADLE_VERSION-bin.zip"
    SHA_FILE="$ZIP_FILE.sha256"
    DIST_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    mkdir -p "$LOCAL_DIR"

    if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
        if [ ! -f "$ZIP_FILE" ] || [ ! -f "$SHA_FILE" ]; then
            echo "Gradle not found. Downloading Gradle $GRADLE_VERSION..."
            download_file "$DIST_URL" "$ZIP_FILE"
            download_file "$DIST_URL.sha256" "$SHA_FILE"
        fi
        if ! command -v sha256sum >/dev/null 2>&1; then
            echo "ERROR: sha256sum is required to verify the Gradle distribution." >&2
            exit 1
        fi
        if ! command -v unzip >/dev/null 2>&1; then
            echo "ERROR: unzip is required to unpack the Gradle distribution." >&2
            exit 1
        fi
        expected_sha="$(tr -d '[:space:]' < "$SHA_FILE")"
        if ! printf '%s  %s\n' "$expected_sha" "$ZIP_FILE" | sha256sum --check --status; then
            echo "ERROR: Gradle distribution checksum verification failed." >&2
            rm -f "$ZIP_FILE" "$SHA_FILE"
            exit 1
        fi
        unzip -tq "$ZIP_FILE" >/dev/null
        unzip -q -o "$ZIP_FILE" -d "$LOCAL_DIR"
    fi
    GRADLE_CMD=("$GRADLE_HOME/bin/gradle")
fi

exec "${GRADLE_CMD[@]}" --no-daemon "$@"
