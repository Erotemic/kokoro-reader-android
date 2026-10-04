# Changelog

## Unreleased

- Fix endpoint-discovery JVM tests to run under Robolectric because the parser uses Android `org.json`, and make the real-server smoke test run multiple syntheses and reject obviously truncated/early-EOS WAV audio.
- Added endpoint-aware TTS discovery: Settings can query server models/voices, filters Wavhost registry entries to installed models, and keeps manual fields as a fallback.
- Added saved per-server profiles so switching among concurrent Kokoro, qwentts, or Wavhost endpoints restores each endpoint's model, voice, format, stream, and language choices.
- Made language overrides portable by sending both generic `language` and Kokoro-compatible `lang_code` fields.
- Hardened the real-server preflight to verify that the configured model as well as voice is actually advertised by the endpoint.
- Added a host-side real-TTS-server preflight that exercises the Android request contract, verifies advertised voices, retains generated audio, and validates WAV responses before device installation.
- Added a pre-install JVM/Robolectric test suite for pagination, immutable TTS configuration, atomic storage, playback-document replacement, service restart semantics, and playback policy decisions.
- Added loopback Kokoro integration tests for successful publication, cache reuse, concurrent request deduplication, active cancellation, truncated responses, and invalid cached audio.
- Added a connected Android lifecycle test that serves valid WAV audio locally, recreates the activity during playback, and verifies service-owned auto-advance to the second page.
- Added reusable test/build scripts and GitHub Actions coverage so tests run consistently outside Android Studio.
- Extracted pure text pagination and playback policy decisions from Android lifecycle code so correctness is directly testable.
- Moved speech generation, page auto-advance, prefetch, and `MediaPlayer` ownership into a foreground playback service.
- Playback now survives activity recreation, rotation, screen-off, Home, and switching to another app.
- Added Android media-session integration with notification, lock-screen, headset, Bluetooth, play/pause, next, and stop controls.
- Added complete audio-focus handling, delayed-focus start, noisy-route pausing, and wake-lock ownership.
- Made the playback service non-sticky so process death cannot resurrect stale speech unexpectedly.
- Added explicit playback-run cancellation that stops queued work, disconnects in-flight HTTP requests, and rejects stale results.
- Captured an immutable TTS configuration per document so settings cannot change voice, server, format, or normalization halfway through a queue.
- Consolidated foreground generation, manual pre-generation, prefetch, cache lookup, history lookup, and in-flight deduplication in one shared audio repository.
- Added atomic document/history writes and atomic audio promotion to prevent partial or concurrently overwritten files.
- Moved the saved editor document out of `SharedPreferences` into an atomic app-private file, with one-time migration of legacy state.
- Serialized Clear History and Clear Audio Cache with request cancellation so late downloads cannot recreate data after deletion.
- Kept a bounded wake lock across the auto-next gap so screen-off playback cannot sleep between pages, while stopping the progress ticker when paused.
- Kept activity/history state pinned to the active document across settings changes and deferred page-size repagination until the next explicit playback run.
- Rejected incomplete fixed-length HTTP responses before cache promotion and suppressed stale utility-network callbacks after activity destruction.
- Coalesced routine history metadata saves so page scrubbing/navigation no longer rewrites and fsyncs the full session JSON for every intermediate UI event.
- Removed raw PCM from selectable formats because Android `MediaPlayer` cannot play headerless PCM directly.
- Removed activity `configChanges` interception so normal Android recreation paths are exercised while playback remains service-owned.
- Disabled Android backup for private document text and generated speech.
- Added a dedicated monochrome notification icon and Android 13+ notification-permission handling.
- Removed the wall-clock build timestamp for reproducible builds and added checksum/integrity verification to the Gradle fallback downloader.
- Added Settings build information with app version, build type, Git SHA, commit date, branch, describe string, clean/dirty tree state, and a copy button.

## 0.6.0

- Polished main-screen spacing and fixed the duplicated previous-page button layout bug.
- Fixed settings-dialog theming so it no longer overwrites the main root view reference.
- Added a saved-history clear action with confirmation.
- Changed saved history audio to live under per-session `audio/` folders while keeping a legacy fallback for older development builds.
- Made `History sessions to keep = 0` consistently disable and prune saved speech history.
- Fixed prefetching to start from the page after the currently playing page, not just the current UI page.
- Improved HUD track status wording for partial/fetching/complete states.
- Added `docs/` specifications, storage design, playback/prefetch notes, UX notes, and a manual test plan.

## 0.5.1

- Added a main-screen **Clear Text** button that clears the current editor/session while preserving speech history and saved audio.

## 0.5.0

- Added bounded speech-history storage with a top-level History button.
- Saved generated audio files alongside history sessions for offline replay when available.
- Added a configurable history retention limit.
- Added HUD track-build status to distinguish server fetching from fully built/offline tracks.
- Prefetches subsequent pages while playback starts to reduce page-transition downtime and improve whole-session progress estimation.
- Added dark / light mode with dark mode as the default.

## 0.4.3

- Generate Android launcher icon resources from root `icon.png` with border-connected white background removal.
- Added adaptive-icon resources so launchers mask the icon without showing an opaque white square.

## 0.4.2

- Added a setting to choose whether the playback bar tracks the entire text/document or only the current page.
- Whole-text progress remains the default; current-page mode keeps the previous page-local behavior.

## 0.4.1

- Use `icon.png` from the project root as the Android launcher icon.

## 0.4.0

- Added Settings -> Health Check for the configured Kokoro server.
- Added main-screen playback controls: play/pause, progress bar, drag-to-seek, and app-level volume.
- Shows estimated stream/download progress while Kokoro audio is still arriving, then exact playback time once the file is ready.

## 0.3.0

- Reworked the app into a car / old-person interface.
- Moved most Kokoro settings behind a Settings dialog.
- Added top-of-screen page arrows and a page seek bar.
- Added a prominent large text/page area.
- Renamed the main action to **Play Clipboard**.
- Renamed current-page playback to **Play text**.
- User-initiated paste into the main text box now auto-paginates without starting playback.
- Page arrows now select pages without auto-playing, so you can choose where to start.
- Settings dialog keeps server, voice, speed, format, language, normalization, cache, load-session, and pre-generation controls.

## 0.2.0

- Added persistent Kokoro request settings.
- Added voice fetching.
- Added response format, language, stream, and normalization settings.
- Added saved session / existing text support.
- Added Play From Page # behavior.

## 0.1.0

- Initial MVP.
