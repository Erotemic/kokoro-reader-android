# Manual Test Plan

Use this checklist after installing a debug build.

## Basic launch

- Launch app.
- Confirm dark mode is enabled by default.
- Confirm the app does not crash before any text is loaded.
- Confirm top row has History and Settings.
- Confirm previous/next buttons are spaced correctly and neither appears twice.

## Settings

- Open Settings.
- Confirm the default server URL is `http://192.168.222.38:8880`.
- Tap Health Check with Kokoro running; expect success.
- Stop Kokoro or point to a bad URL; expect a clear failure message.
- Toggle light mode, Save, confirm the main UI changes.
- Toggle dark mode back on, Save.
- Set history limit to 0, Save, confirm History says disabled/empty.
- Set history limit back to 20, Save.

## Text and pagination

- Paste a short paragraph manually.
- Confirm it paginates and displays page 1/1.
- Paste a long multi-paragraph text.
- Confirm page slider max changes and arrows move pages.
- Edit the visible page and tap Play Text; confirm it treats the edited visible text as a new document.
- Tap Clear Text and cancel; confirm text remains.
- Tap Clear Text and confirm; text/page state clears while history remains.

## Playback

- Load a multi-page text.
- Tap Play Text.
- During generation, confirm HUD says it is receiving/fetching and progress is estimated.
- After playback starts, confirm play/pause toggles.
- Drag the playback bar on the current page; confirm seeking works after audio is prepared.
- Let page 1 finish; confirm auto-next starts page 2 when enabled.
- Disable auto-next and confirm playback stops after the current page.

## Whole-text vs current-page progress

- Enable whole-text progress.
- Start playback from page 1 of a multi-page text.
- Confirm progress does not reset to zero when auto-advancing to page 2.
- Disable whole-text progress.
- Confirm progress is page-local.

## Prefetch

- Set prefetch pages to at least 2.
- Start playback on a multi-page text.
- Watch HUD/audio counts increase as future pages are generated.
- Simulate a brief network/server delay after current page starts; confirm already-prefetched pages continue to play.

## History and offline replay

- Generate at least two pages.
- Open History and confirm the session row shows an audio count.
- Select the history row and confirm it loads and plays.
- Clear transient audio cache.
- Select the history row again; confirm pages with saved audio replay without server generation.
- Tap Clear History and confirm.
- Open History; confirm history is empty.

## Icon

- Build the app with root `icon.png` present.
- Inspect generated launcher icon with `gdalinfo app/build/generated/res/rootLauncherIcon/mipmap-xxxhdpi/ic_launcher.png`.
- Confirm there is an alpha band.
- Install and confirm Android launcher does not show an opaque white square around the icon.
