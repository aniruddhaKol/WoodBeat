# WoodBeat

Android music player and internet radio with a hand-crafted wooden look, in the spirit of the classic desktop players — by **Aniruddha DuttaChowdhury**.

Version **0.1.6** (build 52). Package: `app.adc.woodbeat`. Android 8.0+ (phones and tablets).

[Download APK](https://github.com/aniruddhaKol/WoodBeat/releases/latest)

## Screenshots

Captured on the Android emulator, 19 November 2026.

| Player | Playlist menu | Equalizer |
| --- | --- | --- |
| <img src="docs/screenshots/player.png" width="230" alt="Player with spectrum and playlist"> | <img src="docs/screenshots/playlist-menu.png" width="230" alt="Playlist MISC menu with list options"> | <img src="docs/screenshots/equalizer.png" width="230" alt="10-band equalizer"> |

| EQ presets | Library | Radio |
| --- | --- | --- |
| <img src="docs/screenshots/eq-presets.png" width="230" alt="Equalizer preset menu with Save / Save as"> | <img src="docs/screenshots/library.png" width="230" alt="Library"> | <img src="docs/screenshots/radio.png" width="230" alt="Bengali radio stations"> |

## Features

- **Player:** seven-segment time (tap for remaining), scrolling title, kbps / kHz / stereo, spectrum and oscilloscope (tap to switch), seek, volume, balance, play/pause, stop, shuffle and repeat (all / one) with Winamp-style toggle lights.
- **Playlist:** add files, folders or stream URLs; remove, crop, select; sort, reverse, randomize; save and load lists. Tap or double-tap a row to play it, long-press to select; songs and radio stations can share one list.
- **Equalizer:** preamp + 10 bands (60 Hz – 16 kHz), response curve, built-in and custom presets, optional auto-load by genre.
- **Library:** radio, DLNA (coming soon), all songs, folders, playlists, albums, artists, genres.
- **Internet radio:** saved stations with a starter list of Indian Bengali stations, radio-browser.info search, Play URL. Pausing a station stops it, so Play rejoins the live broadcast; shuffle and repeat are off while a station plays.
- Background playback with notification, lock-screen and Bluetooth controls; opens audio files from file managers.

## Source layout

- `src/main/kotlin/app/adc/woodbeat/` — app code: `PlaybackService` (Media3), `WoodDsp` (EQ, balance and analyser as an ExoPlayer audio processor), pages and wooden widgets.
- `images/` — source artwork; `tools/make_assets.py` turns it into `src/main/res/drawable-nodpi/` (needs Pillow, numpy and Microsoft Edge for the SVG glyphs).
- `docs/screenshots/` — README screenshots.

## Build

Requires Android SDK platform 36, JDK 17 and Gradle 8.13. `build.ps1 -DebugBuild` builds a debug APK into `build/` (paths at the top of the script point to the local toolchain).

Third-party attribution is in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). License: [PolyForm Noncommercial 1.0.0](LICENSE.md).
