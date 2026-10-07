# Device Test Plan

Run the automated pre-install suite first:

```bash
./test_debug.sh
```

Then run the connected lifecycle test on an emulator or disposable test device:

```bash
KOKORO_ALLOW_DESTRUCTIVE_CONNECTED_TESTS=1 ./connected_test.sh
```

See `docs/TESTING.md` for exact automated coverage. Use the remaining checklist after installing a debug build on the target phone.

## Basic launch and recreation

- Launch with no saved text; confirm no crash and an idle HUD.
- Confirm dark mode is the default and top controls are not duplicated.
- Rotate several times while idle; confirm Android recreates the activity cleanly.
- Background and foreground the app; confirm the editor and selected page remain coherent.

## Settings and configuration snapshots

- Confirm the default server URL is `http://10.0.2.2:8880` unless overridden by local build configuration.
- Reachability succeeds against a running TTS server and reports a clear error for a bad endpoint.
- Speech Test succeeds only when the currently edited server/model/voice/format combination both synthesizes and decodes through Android `MediaPlayer`; verify Kokoro `mp3` and the qwentts-compatible format independently.
- Edit a Settings field, press Cancel and Back separately, and verify each path warns before discarding.
- Save two named TTS profiles (for example Kokoro `:8880` / `mp3` and qwentts `:11436` / `wav`), switch between them, and verify the whole endpoint request contract is restored.
- Fetch Voices populates the voice selector.
- Confirm response formats are limited to `mp3`, `opus`, `aac`, `flac`, and `wav`.
- Start a multi-page document, then change voice/server/speed/format while it plays; confirm the active queue keeps its original settings and a newly started document uses the new settings.
- Change chars-per-page while playback is active; confirm the visible/active pages do not jump, then stop and explicitly Play again and confirm repagination occurs at approximately the same document position.
- Toggle light/dark mode and confirm the main UI and dialogs update.
- Set history retention to 0, save, and confirm retained history is pruned; restore it to 20.

## Text and pagination

- Paste short and long text; confirm page count, arrows, and page slider update.
- Share `text/plain` into the app and confirm pagination.
- Edit a visible page and tap **Play Text**; confirm it becomes a new document.
- Clear Text with cancel and confirm nothing changes; confirm Clear Text preserves history.

## Foreground playback

- Start a multi-page document and confirm generation status/progress is visible.
- After preparation, verify play/pause, volume, playback rate, and seek.
- Let a page finish; verify auto-next advances when enabled and stops when disabled.
- Enable whole-text progress and confirm it does not reset at page boundaries.
- Switch to current-page progress and confirm the bar becomes page-local.

## Rotation and background ownership

- Rotate during an active HTTP request; generation continues and stale activity callbacks do not duplicate playback.
- Rotate during playback; audio continues from the same position without restart.
- Press Home or open another app; verify the current page and subsequent pages continue.
- Turn the screen off for longer than one page; verify auto-next and prefetch continue.
- Return to the activity and confirm page, progress, status, and play/pause synchronize with the service.

## Notification and media session

- Confirm the foreground notification shows current page state and play/pause, next, and stop actions.
- Exercise controls from the notification and lock screen.
- Exercise wired-headset and Bluetooth play/pause/next controls.
- Disconnect headphones/Bluetooth during playback; confirm playback pauses rather than switching audibly to the phone speaker.
- On Android 13+, deny notification permission and confirm playback still fails gracefully or continues within platform rules without crashing; grant it and verify controls.

## Audio focus

- Start another media app while Kokoro Reader plays; confirm Kokoro Reader pauses on focus loss.
- Stop the competing audio after transient loss; confirm Kokoro Reader resumes only when appropriate.
- Trigger a permanent focus loss; confirm it remains paused until the user explicitly resumes.
- Request playback while focus is temporarily delayed; confirm audio does not start until focus is granted.

## Cancellation and queue replacement

- Start generation over a throttled connection, press Stop, and verify the request disconnects, no partial audio becomes playable, no page auto-starts later, and the notification disappears.
- Stop while prefetch is active; verify future pages stop appearing in cache/history.
- Clear Audio Cache during a throttled foreground or manual generation; verify the request is cancelled and no late cache file reappears after the clear completes.
- Clear History during a throttled foreground or manual generation; verify no late history directory/audio/metadata reappears after the clear completes.
- Start document A and immediately replace it with document B; verify A cannot resume, overwrite B's state, or publish stale UI progress.
- Rapidly press play/stop/play; verify exactly one active queue and player.

## Process-death semantics

- Start playback, then force-stop or kill the app process from Android tooling.
- Confirm stale speech does not restart later merely because Android recreates the service.
- Reopen the app; confirm saved editor/history data can be selected explicitly, but playback does not begin without user action.

## Prefetch and deduplication

- Set prefetch-ahead to at least 2 and verify future ready counts increase.
- Trigger Settings pre-generation for a page already being prefetched; verify only one Kokoro request occurs for that page/cache key.
- Introduce a brief server outage after pages are prefetched; verify ready pages continue and speculative errors do not spam dialogs.

## History, atomicity, and offline replay

- Generate multiple pages, open History, and verify metadata/audio counts.
- Clear transient cache and replay retained pages with the server offline.
- Interrupt the app/process during metadata write or audio download; relaunch and confirm JSON remains readable and truncated audio is not accepted.
- Exercise rapid page changes while background prefetch updates history; confirm current page and audio counts are not lost.
- Clear History and confirm current editor text remains.
- Upgrade from a build with legacy `lastText` preferences, restore once, and confirm `current_text_session.json` is created and the legacy preference is removed.

## Build and packaging

- Run `./build_debug.sh` without a system Gradle and verify the downloaded distribution checksum and ZIP integrity are checked.
- Build twice from identical source/Git state and confirm no wall-clock build timestamp forces different app inputs.
- Inspect the generated launcher icon alpha and confirm no opaque white square.
- Confirm the notification uses the dedicated monochrome status icon.
