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
