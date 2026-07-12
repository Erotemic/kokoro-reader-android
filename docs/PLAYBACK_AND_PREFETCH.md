# Playback, Progress, and Prefetch Design

## Page-based audio model

Kokoro Reader sends one page/chunk of text at a time to Kokoro-FastAPI. The server returns one audio file per page. Android's `MediaPlayer` plays the current page audio file.

This design avoids generating an extremely long audio file for a whole document, but it means the app needs a document-level abstraction above page-level audio.

## Lifecycle and background ownership

Playback is owned by `PlaybackService`, not `MainActivity`. The service is a media-playback foreground service and owns:

- the active paginated document and current page;
- Kokoro generation and download progress;
- `MediaPlayer`, playback rate, volume, and seeking;
- auto-advance and future-page prefetch;
- audio focus and the partial wake lock;
- the persistent playback notification.

`MainActivity` is a controller and view of the service snapshot. It may be stopped, recreated, rotated, or covered by another app without interrupting the queue. The current document is handed to the service through an app-private JSON file rather than intent extras, avoiding Binder-size limits for long documents. A process restart can reload that durable document when Android restarts the sticky service.

Rotation is also declared as an activity configuration change so ordinary orientation changes do not rebuild the programmatic UI. Correctness does not depend on that optimization: playback remains service-owned even when Android recreates the activity for another reason.

The foreground notification exposes play/pause and stop. Playback requests audio focus and pauses on full or transient focus loss. A partial wake lock is held only while generation or active playback needs the CPU, allowing screen-off playback and page advancement without keeping the display awake.

## Exact progress vs estimated progress

There are three useful progress states:

1. **Receiving Kokoro audio**: the HTTP response is still arriving. Progress is an estimate based on content length when available, otherwise bytes read / elapsed time.
2. **Current page prepared**: Android has a full audio file for the current page and `MediaPlayer` knows the duration. In-page progress is exact.
3. **Whole track complete/offline**: every page has an available audio file. Whole-document progress is still character-weighted between pages, but the track is no longer waiting on the server.

The HUD and playback label should make state 1 visible so the user knows seeking is not fully available yet.

## Whole-text progress

Whole-text progress uses character-count page units:

```text
pageStartUnits[i] = sum(length(page[j]) for j < i)
totalPlaybackUnits = sum(length(page[j]) for all pages)
```

For the active page, `MediaPlayer` current position maps into that page's character-count unit range. This gives natural whole-document progress without requiring timestamps from Kokoro.

Limitations:

- speech speed is not perfectly proportional to character count;
- punctuation, normalization, and voice can affect duration;
- a generated page gives exact current-page time but not word-level timestamps.

## Current-page progress

When Settings disables whole-text progress, the playback bar uses a fixed 0..1000 range for the current page. Dragging seeks only within the selected/current page.

## Seek behavior

When the user drags the playback bar:

- in current-page mode, the target page is the current page;
- in whole-text mode, the target page is found by comparing the progress value to `pageStartUnits`/`pageEndUnits`;
- if that page is already prepared in `MediaPlayer`, seek immediately;
- if not, set a pending seek target, generate or load that page audio, then seek once prepared.

## Auto-advance

When playback completes and **Auto-generate/play next page** is enabled, the app increments `currentPage`, saves session state, updates the UI, and generates/plays the next page.

## Prefetch

The app prefetches the next N pages, where N is **Pages to prefetch ahead while playing**.

Prefetch rules:

- start at the page after the page currently being played;
- skip pages whose audio is already available;
- share the same request/dedup logic as foreground playback;
- never block foreground playback;
- update the HUD as pages become ready.

The dedup key is the transient cache path, which is derived from settings, page index, and page text. This prevents duplicate simultaneous Kokoro calls for the same page.

## Failure handling

Foreground generation failures are shown in the status HUD. Prefetch failures are only announced when explicitly requested through the Settings pre-generation action, so background prefetch does not spam normal listening.
