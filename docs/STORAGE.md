# Storage Design

This document describes where Kokoro Reader stores data and the guarantees around it.

## Storage roots

The app uses app-private storage:

```text
cache/kokoro_audio/
files/current_text_session.json
files/kokoro_history/
files/active_playback_document.json
```

Android owns these paths. Other apps cannot read them under normal sandboxing, and uninstalling or clearing app data removes them. Android cloud/device backup is disabled because these files may contain private reading material and generated speech.

## Transient audio cache

Generated page audio is first downloaded to a temporary file and then atomically promoted to:

```text
cache/kokoro_audio/page_0001_<hash>.mp3
```

The extension follows the encoded response format. Supported formats are `mp3`, `opus`, `aac`, `flac`, and `wav`; raw PCM is not cached as playable media.

The cache hash includes:

- normalized server URL;
- model and voice/mix;
- Kokoro TTS speed;
- response format and stream flag;
- language code;
- all Kokoro normalization options;
- page index and page text.

This prevents reuse across incompatible settings. A file is accepted only after the request completes, data is flushed, and atomic promotion succeeds. Tiny/truncated files are rejected.

The Settings **Clear Audio Cache** action first cancels active foreground and manual generation, then deletes only this disposable cache, not retained history audio. Cache publication and deletion share one process lock, so a late request cannot recreate the cache after the clear operation wins.

## Saved editor document

`files/current_text_session.json` stores the normalized editor document with crash-safe atomic replacement. The selected page remains a small `SharedPreferences` integer. Older releases that stored the full document in preferences are migrated on first restore and the legacy value is removed after a successful file write.

The file is rewritten only when the document text changes, not on every page/progress update.

## Active playback handoff

`files/active_playback_document.json` carries a potentially large document from `MainActivity` to `PlaybackService` without Binder extras. It contains:

- paginated pages, which collectively contain the normalized document;
- selected/current page;
- immutable `TtsConfig`;
- a document identity derived from content and configuration.

This file is written atomically and cleared on Stop, completion, terminal failure, and service destruction. The service is non-sticky, so this handoff is not used to silently resume after process death.

## History root

Retained speech sessions live under:

```text
files/kokoro_history/<session-id>/
```

The session id is a short SHA-256 prefix of normalized full text plus the frozen TTS settings key. Identical text/configuration can reuse a session; different voices, servers, speeds, models, or formats cannot collide.

## Session metadata

Each retained session stores:

```text
files/kokoro_history/<session-id>/session.json
```

The JSON includes:

- session id and timestamps;
- current page and page count;
- character count and title preview;
- normalized full text;
- frozen TTS settings snapshot;
- saved-audio count.

Metadata read-modify-write operations are process-synchronized and use Android `AtomicFile`, preventing activity/service races from producing partial JSON or simple lost updates. Routine page/navigation updates are coalesced before the activity rewrites the full metadata document, while initial session creation remains immediate.

## History audio

Saved history audio is stored below the session directory:

```text
files/kokoro_history/<session-id>/audio/page_0001_<hash>.mp3
```

Audio is copied atomically from a verified cache file. The app still checks the older direct-session layout so early development history remains playable.

## Shared repository ownership

`KokoroAudioRepository` is the single authority for:

- cache path/key construction;
- cache and history lookup;
- Kokoro HTTP generation;
- in-flight request deduplication;
- cancellation/disconnection;
- atomic cache/history publication.

The activity, service, manual pre-generation, and speculative prefetch do not maintain separate lock sets or file policies.

## Retention policy

**History sessions to keep** controls retained sessions:

- `20` is the default;
- values are clamped to `0..200`;
- `0` disables retention and prunes existing history.

Pruning sorts by `updatedAt` descending and removes older session directories. Clear/prune operations share the same process lock as atomic metadata and audio publication, preventing late background writes from recreating deleted history. The transient cache is independent.

## Offline lookup order

Playback lookup order is:

1. verified transient cache;
2. retained history audio;
3. legacy history-audio fallback;
4. Kokoro generation.

A session is fully offline-ready only when every page has a verified local audio file.

## SharedPreferences boundary

SharedPreferences stores only small UI preferences and the selected page. Editor text, active playback state, inspectable session metadata, and binary audio use files. Playback freezes a `TtsConfig` rather than rereading preferences page by page.
