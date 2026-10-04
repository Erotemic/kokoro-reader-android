#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

exec python3 - "$@" <<'PY'
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
import wave

ROOT = Path.cwd()


def parse_dotenv(path):
    values = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key] = value
    return values


dotenv = parse_dotenv(ROOT / ".env")
dotenv_local = parse_dotenv(ROOT / ".env.local")


def config(name, default=""):
    for candidate in (
        os.environ.get(name),
        dotenv_local.get(name),
        dotenv.get(name),
        default,
    ):
        if candidate is not None and str(candidate).strip():
            return str(candidate).strip()
    return ""


server = config("TTS_SMOKE_SERVER_BASE", config("KOKORO_SERVER_BASE", "http://10.0.2.2:8880"))
server = server.rstrip("/")
model = config("TTS_SMOKE_MODEL", "kokoro")
voice = config("TTS_SMOKE_VOICE", "af_bella")
response_format = config("TTS_SMOKE_FORMAT", "mp3").lower()
lang_code = config("TTS_SMOKE_LANG_CODE", "")
text = config(
    "TTS_SMOKE_TEXT",
    "This is a server compatibility test from the Android reader repository."
)

if len(sys.argv) > 1:
    server = sys.argv[1].rstrip("/")

print("Android reader server preflight")
print(f"  server: {server}")
print(f"  model:  {model}")
print(f"  voice:  {voice}")
print(f"  format: {response_format}")
print(f"  lang:   {lang_code or '[blank]'}")


def request(path, method="GET", body=None, timeout=30):
    url = server + path
    data = None
    headers = {"Accept": "application/json, text/plain, */*"}
    if body is not None:
        data = json.dumps(body).encode("utf8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        return urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as ex:
        detail = ex.read(4096).decode("utf8", errors="replace")
        raise RuntimeError(f"HTTP {ex.code} from {url}: {detail}") from ex
    except Exception as ex:
        raise RuntimeError(f"Could not reach {url}: {ex}") from ex


def read_json(path):
    with request(path) as response:
        raw = response.read()
        code = response.status
    try:
        parsed = json.loads(raw.decode("utf8"))
    except Exception as ex:
        raise RuntimeError(f"{path} returned HTTP {code} but not valid JSON: {raw[:500]!r}") from ex
    print(f"OK {path}: HTTP {code}")
    return parsed


health = read_json("/health")
models = read_json("/v1/models")

model_entries = models.get("data", []) if isinstance(models, dict) else []
usable_models = []
model_speakers = []
for item in model_entries:
    if not isinstance(item, dict):
        continue
    model_id = str(item.get("id") or "").strip()
    if not model_id:
        continue
    installed = bool(item.get("installed", True))
    if installed:
        usable_models.append(model_id)
    if model_id == model:
        speakers = item.get("speakers")
        if isinstance(speakers, list):
            model_speakers.extend(str(v) for v in speakers if str(v).strip())
        default_voice = str(item.get("default_voice") or "").strip()
        if default_voice:
            model_speakers.insert(0, default_voice)

if model not in usable_models:
    raise RuntimeError(
        f"Configured model {model!r} is not advertised as usable by server. "
        f"Usable models: {', '.join(usable_models) or '[none]'}"
    )
print(f"OK model: {model} is advertised as usable")

voices_payload = None
voices_path = None
for candidate in ("/v1/audio/voices", "/v1/voices"):
    try:
        voices_payload = read_json(candidate)
        voices_path = candidate
        break
    except Exception:
        pass

voices = list(model_speakers)
if isinstance(voices_payload, dict):
    items = voices_payload.get("voices")
    if items is None:
        items = voices_payload.get("data")
    if isinstance(items, list):
        for item in items:
            if isinstance(item, str):
                voices.append(item)
            elif isinstance(item, dict):
                value = item.get("id") or item.get("name")
                if value:
                    voices.append(str(value))

# Preserve ordering while removing case-insensitive duplicates.
deduped = []
seen = set()
for item in voices:
    key = item.lower()
    if key not in seen:
        seen.add(key)
        deduped.append(item)
voices = deduped

if not voices:
    raise RuntimeError(
        "No voices were advertised by the selected model or compatible voice endpoints. "
        f"Last voice payload: {voices_payload!r}"
    )
if voice.lower() not in {item.lower() for item in voices}:
    raise RuntimeError(
        f"Configured voice {voice!r} is not advertised by server. "
        f"Available voices: {', '.join(voices)}"
    )
print(f"OK voice: {voice} is advertised" + (f" ({voices_path})" if voices_path else " (model metadata)"))

# This intentionally mirrors TtsConfig.requestPayload() rather than sending a
# simplified qwentts-only request. Unknown Kokoro fields are part of the real
# Android compatibility contract we want to exercise.
payload = {
    "model": model,
    "input": text,
    "voice": voice,
    "response_format": response_format,
    "speed": 1.0,
    "stream": True,
    "normalization_options": {
        "normalize": True,
        "unit_normalization": False,
        "url_normalization": True,
        "email_normalization": True,
        "optional_pluralization_normalization": True,
        "phone_normalization": True,
    },
}
if lang_code:
    payload["language"] = lang_code
    payload["lang_code"] = lang_code

out_dir = ROOT / "build" / "server-smoke"
out_dir.mkdir(parents=True, exist_ok=True)
ext = response_format if response_format in {"mp3", "wav", "aac", "flac", "opus"} else "bin"
out_file = out_dir / f"last-response.{ext}"
request_file = out_dir / "last-request.json"
request_file.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf8")

with request("/v1/audio/speech", method="POST", body=payload, timeout=180) as response:
    content_type = response.headers.get("Content-Type", "")
    data = response.read()
    code = response.status

if len(data) < 16:
    raise RuntimeError(f"Speech endpoint returned only {len(data)} bytes")
out_file.write_bytes(data)
print(f"OK /v1/audio/speech: HTTP {code}, {len(data)} bytes, Content-Type={content_type or '[none]'}")

if response_format == "wav":
    if len(data) < 12 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise RuntimeError("Configured WAV response is not a RIFF/WAVE file")
    with wave.open(str(out_file), "rb") as wav:
        channels = wav.getnchannels()
        rate = wav.getframerate()
        frames = wav.getnframes()
        sample_width = wav.getsampwidth()
        duration = frames / rate if rate else 0.0
    if duration <= 0.05:
        raise RuntimeError(f"Generated WAV is implausibly short: {duration:.3f}s")
    print(
        "OK WAV: "
        f"{channels} channel(s), {rate} Hz, {sample_width * 8}-bit, {duration:.2f}s"
    )

print()
print("PASS: server satisfies the Android reader's HTTP/audio preflight.")
print(f"  request: {request_file}")
print(f"  audio:   {out_file}")
PY
