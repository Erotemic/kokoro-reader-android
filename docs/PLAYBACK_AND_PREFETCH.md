# Playback, Progress, and Prefetch Design

## Page-based audio model

Kokoro Reader sends one page/chunk of text at a time to Kokoro-FastAPI. The server returns one encoded audio file per page, and Android's `MediaPlayer` plays the current page file. Supported formats are `mp3`, `opus`, `aac`, `flac`, and `wav`. Headerless raw PCM is intentionally not offered because it requires an `AudioTrack` pipeline rather than `MediaPlayer`.

This design avoids generating one extremely long file, but it requires a document-level state machine above page-level audio.

## Lifecycle and background ownership

`PlaybackService`, not `MainActivity`, owns the active playback run. The media-playback foreground service owns:

- the immutable paginated document and frozen `TtsConfig`;
- current page, pending seek, and playback state;
- Kokoro generation, download progress, cancellation, and prefetch;
- `MediaPlayer`, playback rate, volume, and seeking;
- auto-advance and future-page scheduling;
- audio focus, the partial wake lock, and noisy-route handling;
- the Android `MediaSession` and foreground notification.

`MainActivity` is only a controller and view of service snapshots. Android is allowed to recreate it normally during rotation and other configuration changes. Reattachment verifies the exact active document identity rather than merely matching its page count.

The current document is handed to the service through an app-private JSON file rather than intent extras, avoiding Binder-size limits. This file is a transient command/state handoff, not permission to auto-resume forever. The service uses `START_NOT_STICKY`; a null restart intent stops the service, and Stop/completion/failure clear the active-document file. Android process death therefore cannot produce unsolicited speech later.

The media session and notification expose play/pause, next, and stop to the app, lock screen, headsets, and Bluetooth controllers. Playback waits for audio focus before starting, pauses for transient loss, remains paused after permanent loss, and pauses when an audio route disconnects. A bounded partial wake lock is held while generation or playback requires CPU time and is refreshed across the short auto-next gap; paused playback does not keep a wake lock or a 500 ms progress ticker running.

## Immutable playback configuration

A `PlaybackDocument` captures its `TtsConfig` at queue creation. Server URL, model, voice, TTS speed, response format, stream flag, language code, and normalization options cannot drift between pages. Saving new Settings changes the next document or an explicitly restarted run, never the already-active queue.

The document identity and page cache keys include this configuration. History sessions and generated page files therefore cannot silently mix incompatible settings.

## Cancellation and replacement

Foreground generation, prefetch, and activity-side pre-generation use explicit cancellable work scopes. Stop, document replacement, service destruction, and superseding page work:

1. mark the scope cancelled;
2. make queued executor work exit before publishing results;
3. disconnect tracked `HttpURLConnection`s;
4. invalidate stale callbacks/results;
5. release the player and wake lock when no longer needed.

A cancelled or superseded request cannot publish audio into the active queue. Foreground playback, prefetch, and Settings pre-generation all use the same `KokoroAudioRepository` and shared in-flight key, so identical work is deduplicated across components.

## Exact progress vs estimated progress

There are three useful progress states:

1. **Receiving Kokoro audio**: the HTTP response is still arriving. Progress is estimated from content length when available, otherwise bytes received and elapsed time.
2. **Current page prepared**: Android has a complete audio file and `MediaPlayer` knows its duration. In-page progress is exact.
3. **Whole track complete/offline**: every page has usable cache/history audio. Whole-document progress is still character-weighted between pages, but the track no longer depends on the server.

The HUD distinguishes these states so users know when seeking and offline replay are fully available.

## Whole-text progress

Whole-text progress uses character-count page units:

```text
pageStartUnits[i] = sum(length(page[j]) for j < i)
totalPlaybackUnits = sum(length(page[j]) for all pages)
```

For the active page, `MediaPlayer` position maps into that page's character-count range. This provides stable document-level progress without requiring timestamps from Kokoro.

Limitations:

- speech duration is not perfectly proportional to character count;
- punctuation, normalization, language, and voice affect timing;
- a generated page gives exact page timing but not word-level timestamps.

## Current-page progress

When Settings disables whole-text progress, the playback bar uses a fixed 0..1000 range for the current page. Dragging seeks only within that page.

## Seek behavior

When the user drags the playback bar:

- current-page mode keeps the current page;
- whole-text mode finds the target page from its character-unit range;
- an already prepared target seeks immediately;
- otherwise the service records a pending seek, loads/generates that page, then seeks after preparation.

## Auto-advance

When a page completes and **Auto-generate/play next page** is enabled, the service advances its own current page, atomically updates history metadata, loads or generates the next page, and starts it after audio focus is available. The service retains CPU ownership across the configurable inter-page delay, so screen-off sleep cannot strand the queue between pages. UI visibility is irrelevant.

## Prefetch

The service prefetches the next N pages, where N is **Pages to prefetch ahead while playing**.

Prefetch rules:

- begin after the service's currently playing page;
- skip valid cache/history audio;
- share repository lookup, request, and in-flight deduplication with foreground work;
- remain lower priority than the requested/current page;
- stop promptly when the active run is cancelled or replaced;
- avoid surfacing speculative failures as intrusive UI errors.

## Failure handling

Foreground failures update the service snapshot and HUD, release foreground resources, and clear stale active-document state. Prefetch failures remain quiet unless the user explicitly requested pre-generation. Partially downloaded files are never promoted as valid audio; temporary writes and metadata updates use atomic replacement.
