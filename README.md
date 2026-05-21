# Kokoro Reader Android

A native Android reader for speaking long copied/shared text through a home Kokoro-FastAPI server.

This version is tuned for the workflow you described: **large controls, simple car-friendly main screen, page flipping before playback, and advanced settings hidden behind a menu**.

## Main screen

The main screen intentionally stays simple:

- small status HUD at the top
- **History** button at the top for replaying previous generated sessions
- **Settings** button at the top right
- large **left / right page arrows** at the top of the screen
- page counter and page seek bar
- playback progress bar with play/pause and drag-to-seek after audio is ready
- setting to make that bar track either the whole text/document or just the current page
- stream/download estimate indicator while Kokoro is still returning audio
- HUD track-build status showing whether the session is still fetching server audio or is fully built/offline
- app-level volume slider
- prominent text/page area
- huge **Play Clipboard** button
- smaller **Play text** button
- **Clear Text** button for clearing the current editor/session without deleting speech history

## Core workflow

### Fast path

1. Copy long text on the Pixel.
2. Open Kokoro Reader.
3. Tap **Play Clipboard**.

The app reads the Android clipboard, normalizes line breaks, paginates/chunks the text, generates page 1 through Kokoro, and starts playback. While Kokoro is still returning the audio stream, the playback bar is labeled as an estimate; once the complete audio file is cached and Android prepares it, the bar switches to exact time/duration and can be dragged to seek. By default the playback bar tracks the whole text/document across pages, but Settings can switch it back to current-page-only progress.

### Choose where to start

1. Paste text into the large text area.
2. The app auto-paginates the pasted text.
3. Use the top **◀ / ▶** arrows or the page slider to choose a page.
4. Tap **Play text**.

The arrows only select pages; they do not auto-play. This lets you flip through pages and choose where to start. Use **Clear Text** when you want to empty the current editor/session without deleting saved speech history or offline audio.

### Existing text

The app saves the last text and current page. Reopen the app and it auto-loads the session by default. Then use the arrows/slider to choose a page and tap **Play text**.

You can also open **Settings -> Load Last Session**.

## Settings behind the menu

The Settings dialog includes:

- server URL, e.g. `http://10.0.2.2:8880`
- model, default `kokoro`
- voice or voice mix, e.g. `af_bella` or `af_bella+af_sky`
- **Health Check**, which tries `/health` and then `/v1/audio/voices`
- **Fetch Voices** from `/v1/audio/voices`
- TTS speed sent to Kokoro
- local phone playback rate
- response format: `mp3`, `opus`, `aac`, `flac`, `wav`, or `pcm`
- stream flag
- language code override, blank means auto
- page/chunk size
- auto-generate/play next page
- playback bar scope: entire text or current page only
- auto-load saved session
- Kokoro text normalization toggles
- clear audio cache
- pre-generate next pages
- max speech-history sessions to keep
- prefetch pages-ahead count
- dark / light mode, defaulting to dark

For best Android playback compatibility, start with `mp3`.


## Speech history and offline replay

Generated sessions are saved in app-private storage as a bounded history list. The **History** button opens a table of previous text-to-speech sessions; selecting a row loads that text and starts playback from the saved session. When audio files are available, the app reuses the saved files so old sessions can be replayed offline without calling Kokoro again.

Each history session stores `session.json` plus page audio under an `audio/` subfolder. The history limit is configurable in Settings. Set it to `0` if you do not want old sessions retained; saving settings with `0` prunes saved history. The transient audio cache can still be cleared without deleting saved history audio. A separate **Clear History** action deletes saved session metadata and offline audio without clearing the current editor text.

While playback starts, the app opportunistically prefetches subsequent pages. This helps the whole-text progress bar become more accurate, reduces gaps between pages, and improves resilience to Kokoro/server lag or brief network glitches. The HUD shows whether the track is still fetching or is complete/offline.

## Project documentation

The `docs/` folder now records the intended behavior and maintenance notes:

- `docs/SPEC.md` - product and feature specification.
- `docs/STORAGE.md` - cache/history storage layout and retention policy.
- `docs/PLAYBACK_AND_PREFETCH.md` - progress, seeking, auto-advance, and prefetch design.
- `docs/UX_NOTES.md` - UI/UX polishing guidelines.
- `docs/TEST_PLAN.md` - manual test checklist for debug builds.

## Endpoint assumption

The app calls your Kokoro-FastAPI server with:

```http
POST /v1/audio/speech
Content-Type: application/json
```

and a body like:

```json
{
  "model": "kokoro",
  "input": "...page text...",
  "voice": "af_bella",
  "response_format": "mp3",
  "speed": 1.0,
  "stream": true,
  "normalization_options": {
    "normalize": true,
    "unit_normalization": false,
    "url_normalization": true,
    "email_normalization": true,
    "optional_pluralization_normalization": true,
    "phone_normalization": true
  }
}
```

If the language code field is blank, the app omits `lang_code` and lets Kokoro derive it from the selected voice.

## Build prerequisites on Linux

You need:

- JDK 17+
- Android SDK command-line tools or Android Studio
- Android platform SDK 35
- Android build-tools 35.x
- `adb` for installing on the Pixel 5

A typical SDK install looks like this if you already have `sdkmanager` available:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Set `ANDROID_HOME` if your environment does not already do it:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

## Build

From this project directory:

```bash
./build_debug.sh
```

The script uses your installed `gradle` if available. If Gradle is not installed, it downloads Gradle 8.10.2 into `.gradle-local/` and uses that.

The launcher icon is sourced from `icon.png` in the project root. Keep that file next to `settings.gradle`. The Gradle build generates density-specific launcher PNGs and Android adaptive-icon XML before packaging the APK. If the root PNG is an RGB image with a white background, the generator flood-fills only the border-connected near-white background to transparency, so Android launchers do not show the icon as a white square.

The debug APK will be here:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

## Install on Pixel 5

Enable USB debugging on the phone, connect it, then run:

```bash
./install_debug.sh
```

Or manually:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Cleartext HTTP note

This app intentionally allows cleartext HTTP because it is meant for a private VPN/LAN Kokoro server. Do not ship this configuration as-is for a public internet service.

## Current limitations

- No lock-screen media controls yet.
- No foreground media service yet.
- No sentence/word timestamp seeking yet.
- The main text box auto-paginates user-initiated paste. If you manually edit an already paginated page, tapping **Play text** treats the edited visible text as a new document.
