#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./tools/run_gradle.sh assembleDebug

echo
echo "Built: app/build/outputs/apk/debug/app-debug.apk"
