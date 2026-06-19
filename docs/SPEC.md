# Kokoro Reader Android Feature Specification

This document records the app behavior that has been implemented so future changes can be checked against the intended workflow.

## Product goal

Kokoro Reader is a simple native Android text-to-speech reader for long copied or shared text. It talks to a private Kokoro-FastAPI server on the local network, generates audio page-by-page, and makes generated sessions available for later replay when cached audio is available.

The app is intentionally optimized for a low-friction, large-control workflow on a phone: paste/share text, page through it, start reading, and recover smoothly from server/network latency.

## Main-screen requirements

The main screen should expose only the controls needed during normal listening:

- a compact HUD/status area that explains what the app is doing;
- a **History** button near the top;
- a **Settings** button near the top;
- large previous/next page controls;
- a page indicator and page seek bar;
- a playback progress bar;
- a play/pause button;
- an app-level volume bar;
- a large text/page display area;
- **Play Clipboard**;
- **Play Text**;
- **Clear Text**.

The main controls should be finger-friendly. Controls that are not needed during normal listening belong in Settings or History.

## Text ingestion and pagination

The app accepts text from three paths:

1. Android clipboard via **Play Clipboard**.
2. Android share sheet via the `text/plain` SEND intent.
3. Manual paste/edit in the main text box.

Before pagination, text is normalized by:

- converting CRLF/CR line endings to LF;
- converting tabs and non-breaking spaces to normal spaces;
- removing hyphen-newline joins when followed by a lowercase character;
- collapsing single newlines inside paragraphs to spaces;
- preserving paragraph breaks as blank lines.

Pagination should prefer paragraph boundaries, then sentence boundaries, then conservative hard splits when a single sentence is too large. Page size is configurable with `maxChars`, clamped to a safe range.

## Playback requirements

Playback is page-based because Kokoro generation is page/chunk-based. The app should:

- generate or find audio for the current page;
- play as soon as the current page is ready;
- automatically advance to the next page when the current page finishes when **Auto-generate/play next page** is enabled;
- allow pause/resume once a page is prepared;
- allow dragging the playback bar to seek after the relevant page audio is prepared;
- allow whole-document or current-page progress scope;
- display when progress is an estimate rather than exact playback time.

## Progress-bar modes

The playback bar supports two modes.

### Whole-text mode

Default. The bar represents the entire loaded text. Because the app does not have word-level or sentence-level timestamps, the whole-text timeline estimates each page's share by character count. Once the current page has a prepared `MediaPlayer`, the in-page portion is exact relative to that page's duration.

The whole-text bar should clearly show an estimate marker until all pages have ready audio.

### Current-page mode

The bar represents only the current page. This is useful when listening to shorter pages or manually replaying one page. Dragging seeks within the current page.

## Server/HUD requirements

The HUD should tell the user whether the app is:

- idle/no text;
- preparing/generating the current page;
- receiving audio from the Kokoro server;
- partially built with some pages ready;
- fully built/offline for the loaded text.

The HUD text should avoid ambiguous states like simply saying "cached" without saying whether the whole track is complete.

## Prefetch requirements

When playback starts, the app should opportunistically fetch subsequent pages according to the **Pages to prefetch ahead while playing** setting. Prefetching should:

- start from the page after the page currently being played;
- avoid duplicate simultaneous generation for the same cache key;
- reuse existing transient cache or saved history audio;
- not block current-page playback;
- improve whole-text progress estimation and reduce downtime between pages.

## History requirements

The app keeps a bounded speech history of generated sessions.

- The maximum number of retained sessions is configurable.
- `0` disables history retention and clears retained history on save/prune.
- The History button opens a table of saved sessions.
- Each row should show enough information to choose an old session: updated time, saved audio count, voice, and text title.
- Selecting a history row loads that session, restores its saved TTS settings, and starts playback.
- Saved history audio should be reused before calling the Kokoro server.
- Clearing the transient audio cache must not delete saved history audio.
- Clearing text must not delete saved history audio.

## Offline replay requirements

When generated audio exists in history storage, old sessions should be replayable without contacting Kokoro. This is best-effort: offline replay is only possible for pages whose audio files were generated and saved. If an old session is missing a page's audio, the app may call Kokoro again when online.

## Settings requirements

Settings should include:

- server URL;
- health check;
- voice fetch;
- model;
- voice/mix;
- Kokoro TTS speed;
- local playback rate;
- response format;
- language code override;
- page/chunk size;
- history retention limit;
- dark/light mode;
- stream request flag;
- auto-next flag;
- pages to prefetch ahead;
- progress-bar scope;
- auto-restore flag;
- Kokoro normalization toggles;
- load last session;
- open history;
- pre-generate next pages;
- clear transient audio cache;
- clear saved history.

## Theme requirements

Dark mode is the default. Light mode is available in Settings. Because the app is currently programmatic UI rather than XML layouts, theme colors are applied explicitly to the view tree.

## App icon requirements

The repo-root `icon.png` is the source of truth for the launcher icon. During Gradle build, generated mipmap resources should be created from `icon.png`. If the source image has an RGB white background, only border-connected near-white background pixels should be made transparent so Android launchers do not show an opaque white square.
