# Automated Testing

The repository has two complementary automated test layers.

## Fast pre-install suite

Run:

```bash
./test_debug.sh
```

This executes `testDebugUnitTest`, including plain JVM tests, Robolectric Android tests, and loopback HTTP integration tests. It does not install an APK or require a connected device.

The suite verifies:

- copied-text normalization and bounded pagination
- immutable TTS configuration serialization and request payloads
- endpoint discovery parsing for Wavhost/qwentts model and voice response shapes
- bounded per-server profile persistence and replacement
- cache-key changes when voice/server/format settings change
- atomic JSON mutation, copying, conditional deletion, and editor storage
- playback-document identity, frozen settings, and replacement-race protection
- successful Kokoro generation and atomic cache/history publication
- cache reuse without a second network request
- cross-caller in-flight request deduplication
- cancellation of a throttled response without publishing partial audio
- rejection of truncated fixed-length HTTP responses
- rejection of undersized cached audio
- non-sticky service restart behavior
- playback notification-channel creation
- fail-closed startup when no playback document exists
- serialized Clear Cache and Clear History commands
- audio-focus, auto-next, and seek-offset policies

The HTML report is written to:

```text
app/build/reports/tests/testDebugUnitTest/index.html
```

## Connected Android lifecycle suite

Start an emulator or connect an authorized device, then run:

```bash
KOKORO_ALLOW_DESTRUCTIVE_CONNECTED_TESTS=1 ./connected_test.sh
```

The connected test starts a loopback Kokoro-compatible HTTP server, generates valid WAV responses, starts real foreground playback, recreates `MainActivity`, and verifies that the service-owned queue remains active and auto-advances to page two.

The report is written to:

```text
app/build/reports/androidTests/connected/debug/index.html
```

This test is intentionally separate because it exercises the platform `MediaPlayer`, foreground-service lifecycle, notification plumbing, and activity recreation. Those behaviors cannot be proven by JVM stubs alone.

## Continuous integration

`.github/workflows/android-tests.yml` runs the pre-install suite for pushes and pull requests and uploads the HTML report even when a test fails.

## Remaining device checks

The connected test proves the primary rotation/auto-next path. Screen-off execution, Bluetooth/headset routing, manufacturer battery management, lock-screen controls, and notification-permission UX still need the focused manual checks in `docs/TEST_PLAN.md` on representative hardware.

## Real server compatibility preflight

The normal JVM/Robolectric suite deliberately uses a loopback fake server so CI
is deterministic and never depends on a machine on the LAN. Before installing
an APK against a new real TTS backend, run the separate host-side preflight:

```bash
./server_smoke_test.sh
```

The script reads `TTS_SMOKE_*` values from `.env.local`, `.env`, or the process
environment and falls back to `KOKORO_SERVER_BASE` for the server URL. It:

- checks `/health`;
- reads `/v1/models` and verifies the configured model is advertised as usable (including Wavhost `installed` state);
- reads `/v1/audio/voices` with `/v1/voices` fallback, merges model speaker metadata, and verifies the configured voice is advertised;
- POSTs `/v1/audio/speech` using the same request shape as
  `TtsConfig.requestPayload()`, including the Kokoro normalization fields;
- saves the exact request and generated audio under `build/server-smoke/`;
- validates a configured WAV response as a real RIFF/WAVE file and reports its
  sample rate and duration.

For the qwentts.cpp Q8 backend, configure the ignored local `.env` once with the
same reachable LAN/VPN endpoint you intend to enter on the phone. Set the first value to that concrete URL:

```dotenv
TTS_SMOKE_SERVER_BASE=
TTS_SMOKE_MODEL=qwen-0.6-customvoice-q8-ggml
TTS_SMOKE_VOICE=ryan
TTS_SMOKE_FORMAT=wav
TTS_SMOKE_LANG_CODE=
```

Then run:

```bash
./test_debug.sh
./server_smoke_test.sh
```

`test_debug.sh` proves the application logic against deterministic local test
servers. `server_smoke_test.sh` proves the selected real backend accepts the
Android request contract and returns playable-looking audio. Neither test proves
that the phone can route to the server; for that final networking boundary, use
the app's Settings -> Health Check or a connected-device test.

### Real-server audio sanity

For WAV responses, `server_smoke_test.sh` validates more than the RIFF header. It also
runs three synthesis requests by default (configurable with `TTS_SMOKE_RUNS`) and
requires a conservative minimum duration based on input length for each. This catches valid but
obviously truncated/early-EOS responses (for example, a fraction of a second of audio
for the default full-sentence smoke text) before a phone install.

