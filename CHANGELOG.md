# Changelog

All notable public changes to Ketvoice are documented here. The app UI is French; release notes are in English.

## 2.3.1 — 2026-10-21

### Security

- Removed all development-only credentials and private endpoints from the default settings. The public source and APK no longer contain a Spotify `sp_dc` cookie or private add-on URLs/tokens.
- Add-on search and stream resolution now skip blank endpoints safely.
- Published the first clean public source snapshot and APK.

### Build

- `versionCode` 10, `versionName` 2.3.1.
- JVM tests: 36 tests across 6 suites, 0 failures.
- Android lint: 0 errors.

> Binaries for 1.2.0 through 2.3.0 are withheld because they embedded development-only credentials. Version 2.3.1 is the first public runtime build with no bundled credentials.

## 2.3.0 — 2026-10-21

### Added

- Custom HTTPS `.json` sources with direct manifests, `baseUrl` endpoints, and BitChord-compatible add-ons.
- Real source validation through a test search, quality negotiation, explicit download endpoints, and `allowDownloads` support.
- YouTube Music web-session integration with encrypted local storage, account validation, account dialog, and history sync only when YouTube accepts the tracking request.
- Artist search with a dedicated artist page: profile image, description, monthly listeners, albums, and tracks.
- Album pages resolved from real `MPRE...` YouTube Music identifiers.
- Optional personalized YouTube Music home feed.
- Real Spotify Canvas pipeline through Pathfinder and the user's Spotify session, with Genius fallback and local video cache.
- User-selectable accent color, applied across the interface, sliders, progress, switches, and selected states.
- Settings for real sources, Canvas, playback quality, downloads, lyrics, translation, cache, theme, and launcher icon.

### Changed

- Removed the mock account system and all fake account state. The account area now reflects the real YouTube Music session.
- Replaced fake settings with functional settings and sub-menus.
- Improved artwork resolution with HTTPS-only URLs, retry handling, Deezer direct matches, and iTunes fallback.
- Removed the visible square/background around the home logo.
- Removed the hard border at the bottom of the player artwork and added a long fade into the ambient background.
- Tapping the compact artwork from lyrics returns to the artwork page without opening the action menu.
- Removed artwork zoom interactions.
- Enlarged and strengthened the lyric typography and preserved real segment timing for the highlight animation.
- Improved the mini-player, navigation lens, three-dot indicator, and haptics.
- Improved download quality handling and custom-source fallback behavior.

### Fixed

- YouTube search is restricted to official audio tracks with an artist and album; community uploads are not used as a fallback.
- Slowed, sped-up, instrumental, type-beat, and re-upload variants require an explicit search.
- Canvas downloads are disabled when the animated-artwork setting is off.
- The “Flou des paroles inactives”, “Pochette animée”, and “Canvas en données mobiles” settings are now persisted.

## 2.2.0 — 2026-10-20

### Changed

- YouTube Music search now requires verified audio tracks (`MUSIC_VIDEO_TYPE_ATV`) with an artist and album. Community videos are no longer a fallback, and older unverified entries are re-resolved before playback.
- Slowed, sped-up, and similar variants require an explicit search.
- Lyrics use a 33 sp Black weight with a wider layout. The glow progresses over the real duration of each source-provided segment. Syllables and durations are never fabricated.
- Lyric controls hide after two seconds without contact. Tapping the artwork brings them back. A vertical swipe on the upper area returns to the mini-player.
- Translations appear as small subtitles below each original line, using TTML translations first, then cache, then progressive loading near the active line.
- Source and granularity are labeled in French: syllable-by-syllable, word-by-word, line-by-line, or unsynchronized text.
- Removed artwork zoom. The ambient background is blurred and colored from the artwork, with brightness adaptation.
- Navigation tabs close search, remain available in compact mode, and support long-press plus drag with a Liquid Glass lens, haptic feedback, and a three-dot indicator.
- Removed demo collections from Explore, Library, and recent searches. Genres and albums open real catalog identifiers; personal collections come from history, favorites, and downloads.

### Limitations

- Lyrics depend on provider coverage. A line-by-line source is not turned into fabricated syllable timing.
- Public translation providers may rate-limit requests.
- The player was validated through extracted video frames, not through a physical Android device in this development environment.
- AGSL refraction requires Android 13 or newer; Android 12 keeps the compatible blur path.

## 1.0.0 — 2026-10-20

- Initial Kotlin/Jetpack Compose Android implementation.
- Liquid Glass foundation, home/explore/library screens, custom full-screen player, queue, lyrics, downloads, and settings.
- Four archived build variants were preserved: `1.0.0`, `1.0.0-compat`, `1.0.0-fix1`, and `1.0.0-fix2`.
- These builds did not contain the development credentials later added to intermediate builds. They are archived for reference and are not supported.

## Earlier internal builds

The following build artifacts were created during development:

- `1.2.0`
- `1.3.0`
- `1.3.1`
- `2.0.0`
- `2.1.0`

No complete changelog or matching source snapshot was preserved for these milestones. Their binaries embedded development-only credentials and are intentionally not published. They are superseded by 2.3.1.
