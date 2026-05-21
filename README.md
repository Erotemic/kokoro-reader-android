# Kokoro Reader Android

A native Android reader for speaking long copied/shared text through a home Kokoro-FastAPI server.

This version is tuned for the workflow you described: **large controls, simple car-friendly main screen, page flipping before playback, and advanced settings hidden behind a menu**.

## Main screen

The main screen intentionally stays simple:

- small status HUD at the top
- **Settings** button at the top right
- large **left / right page arrows** at the top of the screen
- page counter and page seek bar
- prominent text/page area
- huge **Play Clipboard** button
- smaller **Play text** button

## Core workflow

### Fast path

1. Copy long text on the Pixel.
2. Open Kokoro Reader.
3. Tap **Play Clipboard**.

The app reads the Android clipboard, normalizes line breaks, paginates/chunks the text, generates page 1 through Kokoro, and starts playback.

### Choose where to start

1. Paste text into the large text area.
2. The app auto-paginates the pasted text.
3. Use the top **◀ / ▶** arrows or the page slider to choose a page.
4. Tap **Play text**.

The arrows only select pages; they do not auto-play. This lets you flip through pages and choose where to start.

### Existing text

The app saves the last text and current page. Reopen the app and it auto-loads the session by default. Then use the arrows/slider to choose a page and tap **Play text**.

You can also open **Settings -> Load Last Session**.

## Settings behind the menu

The Settings dialog includes:

- server URL, e.g. `http://10.0.2.2:8880`
- model, default `kokoro`
- voice or voice mix, e.g. `af_bella` or `af_bella+af_sky`
- **Fetch Voices** from `/v1/audio/voices`
- TTS speed sent to Kokoro
- local phone playback rate
- response format: `mp3`, `opus`, `aac`, `flac`, `wav`, or `pcm`
- stream flag
- language code override, blank means auto
- page/chunk size
- auto-generate/play next page
- auto-load saved session
- Kokoro text normalization toggles
- clear audio cache
- pre-generate next 3 pages

For best Android playback compatibility, start with `mp3`.

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
