# Changelog

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
