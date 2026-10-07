#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# This is deliberately a realistic paragraph rather than "Hello world.".
# Qwen3-TTS short utterances were a misleading subjective quality gate during
# Pascal debugging, while paragraph-length speech reflects the reader workload.
DEFAULT_TEXT='Today we are going to talk about how computers represent information. A computer ultimately works with patterns of numbers, but those numbers can represent text, images, music, and even complex simulations. The important idea is that the meaning comes from how we choose to interpret those patterns.'
TEXT="${TTS_LISTEN_TEXT:-$DEFAULT_TEXT}"

# Reuse the real-server preflight so this gate sends the same request shape as
# the Android reader and performs discovery/compatibility checks first.
TTS_SMOKE_RUNS=1 \
TTS_SMOKE_FORMAT=mp3 \
TTS_SMOKE_TEXT="$TEXT" \
./server_smoke_test.sh

SRC="$ROOT_DIR/build/server-smoke/last-response.mp3"
OUT_DIR="$ROOT_DIR/build/listen-gate"
OUT="$OUT_DIR/phone-path.mp3"
mkdir -p "$OUT_DIR"
cp "$SRC" "$OUT"

echo
echo "Listen gate artifact: $OUT"
if command -v ffprobe >/dev/null 2>&1; then
    ffprobe -hide_banner "$OUT" || true
fi

if [[ "${TTS_LISTEN_NO_AUTOPLAY:-0}" != "1" ]]; then
    if command -v cvlc >/dev/null 2>&1; then
        cvlc --play-and-exit "$OUT"
    elif command -v vlc >/dev/null 2>&1; then
        vlc --play-and-exit "$OUT"
    elif command -v ffplay >/dev/null 2>&1; then
        ffplay -autoexit -nodisp "$OUT"
    else
        echo "No cvlc/vlc/ffplay found; play $OUT manually." >&2
    fi
else
    echo "Autoplay disabled; play $OUT manually."
fi

echo
read -r -p 'Did you hear clear, intelligible Ryan speech for the full paragraph? [y/N] ' answer
case "${answer,,}" in
    y|yes)
        echo "PASS: human listen gate accepted."
        ;;
    *)
        echo "FAIL: human listen gate rejected." >&2
        exit 1
        ;;
esac
