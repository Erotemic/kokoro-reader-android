#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./tools/run_gradle.sh testDebugUnitTest

echo
echo "Unit and Robolectric reports: app/build/reports/tests/testDebugUnitTest/index.html"
