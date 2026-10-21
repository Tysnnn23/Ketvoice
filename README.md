<p align="center">
  <img src="docs/assets/logo.png" width="112" alt="Ketvoice logo" />
</p>

<h1 align="center">Ketvoice</h1>

<p align="center">
  A native Android music player built with Kotlin and Jetpack Compose, with a real Liquid Glass interface, a fully custom player, synchronized lyrics, YouTube Music integration, Spotify Canvas support, and user-provided JSON music sources.
</p>

<p align="center">
  <a href="https://github.com/Tysnnn23/Ketvoice/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Tysnnn23/Ketvoice?display_name=tag&sort=semver"></a>
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
</p>

## Overview

Ketvoice is an independent Android music client whose visual language is inspired by Apple Music and iOS 26. The UI is entirely in French. It is not affiliated with Apple, YouTube, Spotify, BitChord, or any music provider.

The current public release is **2.3.1**. It is an Android 12+ build with `minSdk 31` and `targetSdk 36`.

> The APK is debug-signed for personal installation and testing. It is not a Play Store release.

## Features

### Interface

- Real Liquid Glass panels powered by the Kyant0 Backdrop SDK, with adaptive tint, refraction, blur, and rim lighting.
- Light and dark themes with a user-selectable accent color applied across controls, progress bars, switches, and selection states.
- Home, Explore, and Library tabs, floating glass navigation, a collapsible mini-player, and animated transitions throughout.
- Fully custom full-screen player: artwork, canvas, scrubbing, transport controls, volume, queue, lyrics, and bottom sheets.
- Adjustable launcher icon variants.
- Transparent Ketvoice logo and gradient artwork treatment without a visible image border.

### Playback and storage

- Media3/ExoPlayer playback with a foreground `MediaSessionService`.
- Queue management, previous/next, shuffle, repeat, favorites, and playback history.
- Audio cache with a configurable limit from 0.5 GB to 10 GB.
- Download queue with FLAC/MP3 quality modes and per-track progress.
- Permanent image cache with retries for missing artwork.
- Fast-start stream resolution followed by a higher-quality stream when available.

### YouTube Music and catalog

- Official YouTube Music search restricted to verified audio tracks with an artist and album.
- Artist search with a dedicated artist page: real profile image, description, monthly listeners, albums, and tracks.
- Album pages resolved from real YouTube Music album identifiers.
- Personalized YouTube Music home feed when a valid web session is connected.
- First-party Google sign-in through a WebView, or an explicit manual `SAPISID` cookie fallback.
- The YouTube session is stored locally with Android Keystore encryption. The app only reports “connected” after YouTube validates the session.
- Listening history synchronization only when YouTube accepts the tracking request. No fake history or fake account state is created.

### Lyrics

- Multiple lyric providers, including TTML, LRC, and plain-text sources.
- Real word-by-word or syllable-by-syllable highlighting only when the provider supplies the timing data. Timings are never invented.
- Translation subtitles displayed below each original line, using existing TTML translations, cache, and public translation providers.
- Karaoke-style highlight progression based on the actual segment duration.
- Controls hide after two seconds; tapping the artwork brings them back.
- Swipe down from the lyrics header to return to the mini-player.

### Custom JSON sources

- Add an HTTPS `.json` manifest from **Search → Sources** or **Settings**.
- Supports direct manifests, `baseUrl` + relative endpoints, and BitChord-style addons.
- Search, stream, and download endpoints with placeholders such as `{query}`, `{id}`, `{quality}`, `{title}`, and `{artist}`.
- Search-result validation and a real test search before a source can be saved.
- Quality negotiation for BitChord manifests (`LOSSLESS`, `HIGH`, `LOW`, and variants such as `high`, `normal`, `low`).
- Respects `allowDownloads: 0` and prefers an explicit download endpoint when one is declared.
- See [docs/SOURCES.md](docs/SOURCES.md) for the manifest format.

### Spotify Canvas

- Real Canvas lookup through Spotify Pathfinder, with a Genius fallback.
- Canvas video is cached locally and shown in the player when available.
- Canvas download can be restricted to Wi-Fi.
- Requires a user-provided Spotify `sp_dc` cookie. No Spotify credential is bundled with the app.

### Settings

- Real, persisted settings for theme, accent color, launcher icon, playback quality, downloads, storage, lyrics, translation, Canvas, and YouTube Music.
- Choice rows open a sub-menu instead of silently changing a value.
- Cache size and cache clearing reflect real files on the device.
- No demo account or fake “connected” state.

## Download

Download the latest APK from the [Releases](https://github.com/Tysnnn23/Ketvoice/releases/latest) page.

- Minimum Android version: **Android 12 (API 31)**
- Package: `com.ketvoice.app`
- Signing: Android debug certificate
- Verify the SHA-256 checksum shown in the release notes after downloading.

No credentials, cookies, API tokens, or private add-on URLs are included in the public source or the release APK. Configure your own sources and Spotify Canvas cookie in the app settings.

## Build from source

### Requirements

- JDK 17 or newer
- Android SDK Platform 36 and Build Tools 36
- Android Studio or a command-line Android SDK
- Network access for Gradle dependencies

Set `ANDROID_HOME` (or create `local.properties` with `sdk.dir=...`) and run:

```bash
./gradlew assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

Run the JVM tests and lint with:

```bash
./gradlew testDebugUnitTest lintDebug
```

### Release signing

The project intentionally ships only a debug build configuration. Add your own release keystore and signing configuration before publishing a production build. Never commit keystores, passwords, cookies, or tokens.

## Project structure

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/com/ketvoice/app/
│       │   │   ├── data/       # catalog, sources, settings, lyrics, cache
│       │   │   ├── glass/      # Liquid Glass rendering
│       │   │   ├── player/     # Media3 playback and session service
│       │   │   ├── theme/      # colors and typography
│       │   │   └── ui/         # screens, player, settings, overlays
│       │   ├── res/
│       │   └── AndroidManifest.xml
│       └── test/               # JVM unit tests
├── docs/
│   ├── SOURCES.md
│   └── assets/logo.png
├── CHANGELOG.md
├── PRIVACY.md
├── SECURITY.md
└── README.md
```

### Main dependencies

- Kotlin 2.2 and Jetpack Compose
- AndroidX Media3 / ExoPlayer
- Coil image loading
- Gson
- Kyant0 Backdrop
- JUnit 4 and org.json for JVM tests

## Status and limitations

- The current release is built and validated with JVM unit tests and Android lint. It has not been through a device-farm or Play Store validation process.
- YouTube Music, lyrics, Canvas, artwork, and custom sources depend on third-party services that can change or rate-limit requests.
- A YouTube web session may be rejected or require additional verification. The app never fabricates a connected state.
- Search history cannot be read back from YouTube Music; only accepted playback events are submitted.
- Canvas availability depends on Spotify having a Canvas for that track and on a valid user session.
- Custom sources that require POST requests, authentication, or a search endpoint that cannot return JSON for a test query are rejected.
- The UI is French by design. Source comments and internal identifiers may also be in French.

## Security and privacy

See [SECURITY.md](SECURITY.md) and [PRIVACY.md](PRIVACY.md).

- No Ketvoice account or Ketvoice backend is used.
- No analytics or advertising SDK is included.
- YouTube session data is encrypted locally with Android Keystore.
- User-provided cookies and source URLs remain on the device and are not sent to a Ketvoice server.
- Clearing app data or uninstalling the app removes local session and cache data.

## Versioning and legacy builds

The latest public version is **2.3.1**. See [CHANGELOG.md](CHANGELOG.md) for the full release history.

Earlier development builds from **1.2.0 through 2.3.0** contained development-only Spotify cookies and private add-on endpoints in their default settings. Those binaries are intentionally not published. Version **2.3.1** is the first clean public APK. The original **1.0.0** builds never contained those credentials and are archived as-is in the legacy release.

## Legal

Ketvoice is an independent project for personal use. It is not affiliated with or endorsed by Apple, Google, YouTube, Spotify, Deezer, Genius, LRCLIB, BitChord, or any other music service. All trademarks belong to their respective owners.

You are responsible for complying with the terms of service of any provider you connect and with the copyright laws that apply to you. The project does not host or distribute music. The source is published for transparency and personal experimentation; no license for redistribution is granted unless a separate license file is added by the author.

## Acknowledgements

- The Liquid Glass rendering uses [Kyant0 Backdrop](https://github.com/Kyant0/Backdrop).
- The custom-source protocol is compatible with the BitChord add-on format.
- Artwork, metadata, lyrics, and canvas data are retrieved from their respective public providers at runtime.
