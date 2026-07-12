# UX Notes and Polishing Guidelines

## Main screen layout

The main screen should remain usable one-handed and while the phone is mounted. That means:

- high-frequency controls are large;
- low-frequency controls are in Settings;
- the page display consumes the remaining height;
- labels are short but stateful;
- buttons use plain mixed-case text instead of all caps.

## HUD wording

Prefer actionable state over implementation detail:

- Good: `Track: fetching (2/9 ready)`
- Good: `Track: complete/offline`
- Good: `Receiving Kokoro audio: 42%`
- Avoid: `Task running` or `cached` without scope.

The HUD should be allowed to change often during generation, but it should not flicker between redundant states.

## History UX

The History screen is a simple table. It favors recognition over configuration:

- newest sessions first;
- updated time in the first column;
- audio availability as `ready/pageCount`;
- voice/mix visible;
- text preview/title last.

Selecting a row should load the session and start playback immediately because history is primarily for replaying old speech.

## Settings UX

Settings is allowed to be dense because it is not the normal listening surface. It should still group related controls:

1. server and voice discovery;
2. TTS/playback parameters;
3. pagination/history/playback behavior;
4. normalization options;
5. session/cache maintenance.

Dangerous operations should use confirmation prompts. Currently this includes clearing the current text and clearing saved speech history.

## Theme UX

Dark mode is default for comfortable listening. Light mode exists for bright conditions. Since the UI is programmatic, new UI code should call `applyThemeToTree` on dialog content and `applyAppTheme` after changing the persisted mode.

## Future UX candidates

These are intentionally not implemented yet, but the current architecture should not block them:

- Bluetooth media-button handling;
- lock-screen controls;
- history export/import;
- per-session delete from History;
- favorites/pinned sessions;
- per-word or per-sentence timestamps if the TTS server can provide them;
- Wi-Fi/VPN reachability diagnostics in Health Check.
