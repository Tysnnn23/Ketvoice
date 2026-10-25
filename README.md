<p align="center">
  <img src="docs/assets/logo.png" width="112" alt="Ketvoice logo" />
</p>

<h1 align="center">Ketvoice</h1>

<p align="center">
  A native Android music player built with Kotlin and Jetpack Compose, with a real Liquid Glass interface, a fully custom player, synchronized lyrics, YouTube Music integration, Spotify Canvas support, and music-source search brokered by the Ketvoice API.
</p>

<p align="center">
  <a href="https://github.com/Tysnnn23/Ketvoice/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Tysnnn23/Ketvoice?display_name=tag&sort=semver"></a>
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white">
</p>

## Overview

### 3.0.1 — immersive artist profiles (validation pending)

Artist pages now feature an immersive portrait header with a color-matched
gradient and biographies translated to the selected interface language. Tapping
an artist in the full-screen player opens the profile while playback continues
in the mini-player. The source is versionCode 26 for mobile and Quest. Public
APK downloads will be linked here after on-device release checks are complete.
See the [3.0.1 release notes](docs/releases/v3.0.1.md) for current validation status.

Ketvoice is an independent Android music client whose visual language is inspired by Apple Music and iOS 26. French is the default interface language; Settings offers 11 languages across the app. It is not affiliated with Apple, YouTube, Spotify or any music provider.

The stable release is **2.12.0** (`versionCode` 21) for both **mobile/tablet** (Android 12+, target SDK 36) and **Quest 3/3S** (minimum SDK 32, target SDK 34, landscape-only window). It adds local playlists, a public-link Spotify playlist import with explicit incompleteness warnings, selected design finishes with individual switches, and a fully audited translation catalog. Owner-only imported-media editing, Canvas controls, Sonic Canvas, offline capsules and single-device Wi-Fi handoff remain. Ketogether group listening is deferred. See [2.12.0 notes](docs/releases/v2.12.0.md).

Music-source search, stream resolution, Spotify Canvas, and published-content lookup go through two Supabase Edge Functions: **Ketvoice API** for sources/Canvas and **Ketvoice Content** for public publications. The APK contains only the project’s public Supabase `anon` key and function URLs. Spotify and source credentials remain in Supabase Vault and are never shipped with the app.

> The APK is debug-signed for personal installation and testing. It is not a Play Store release.

## Features

### Quest 3 / Quest 3S window build

- **Landscape only, with the mobile style**: original artwork/Canvas, icon controls, Liquid Glass, floating navigation, and lyric animations. In lyrics/queue mode, artwork, title, artist, and transport stay on the left; lyrics or queue fill the right. No giant text-button dashboard, tablet rail, or portrait-phone frame.
- Pointer hover light on interactive controls, optional lyric sizing, foreground keep-awake, and the sleep timer in the ordinary track ⋯ menu.
- Download **[Ketvoice-2.12.0-Quest.apk](https://github.com/Tysnnn23/Ketvoice/releases/download/v2.12.0/Ketvoice-2.12.0-Quest.apk)** and follow [Quest installation instructions](docs/QUEST.md). Headset-targeted manifest and arm64 support are included; physical Quest hardware validation is still required. This is a 2D app window, not an immersive VR environment. The same-version **[mobile/tablet 2.12.0 APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v2.12.0/Ketvoice-2.12.0.apk)** is available in the same release.

### Interface

- Real Liquid Glass panels powered by the Kyant0 Backdrop SDK, with adaptive tint, refraction, blur, and rim lighting.
- Light and dark themes with a user-selectable accent color applied across controls, progress bars, switches, and selection states.
- Home, Explore, and Library tabs, floating glass navigation, a collapsible mini-player, and animated transitions throughout.
- Adaptive layouts driven by real window constraints: a glass navigation rail at tablet widths, a reading-width Settings page, multi-column track grids, and a two-pane player with artwork/controls beside lyrics or queue. Short landscape windows keep the phone player instead of squeezing two panes.
- Artwork transitions between the full player and mini-player with a fading cover flight in both directions.
- A redesigned Library with local music, collection cards, artists, albums, recent plays, and offline downloads. Collections reflect actual listening history and downloads.
- Local playlists: create, rename, delete, reorder, remove and play; add a song from its menu. Playlists never leave the device and keep no expiring stream URLs.
- Spotify playlist import by public link: preview visible titles, confirm that the preview may be incomplete, then match them in Ketvoice. Unmatched titles stay visible and can be resolved by hand; no Spotify sign-in, cookie or audio download is used.
- Seven selectable design finishes (D1, D2, D3, D4, D5, D7, D8), each with its own switch in Settings → Design Ketvoice.
- French, English, Hindi, Japanese, Chinese, Russian, Italian, Spanish, German, Portuguese, and Korean interface languages. Song titles, artist names, and original lyrics stay unchanged.
- Fully custom full-screen player: artwork, canvas, scrubbing, transport controls, volume, queue, lyrics, and bottom sheets.
- Adjustable launcher icon variants.
- Transparent Ketvoice logo and gradient artwork treatment without a visible image border.

### Playback and storage

- Media3/ExoPlayer playback with a foreground `MediaSessionService`.
- Native equalizer on the real playback audio session, with five bands, presets, and ±12 dB controls clamped to what the device supports.
- Same-Wi-Fi handoff to another Ketvoice instance with explicit receiver confirmation; catalog metadata, queue, and position only — no account tokens, files, or private URLs.
- One motion system with reduced-motion support; lyrics follow and karaoke stay accurate while decorative movement is removed.
- Queue management, previous/next, shuffle, repeat, favorites, and playback history.
- Audio cache with a configurable limit from 0.5 GB to 10 GB.
- Real downloads with FLAC/MP3 quality modes, progress, HTTPS redirects, audio validation, retry/resume, and alternative streams when a provider fails.
- Downloads include the best available lyric timing (native syllables → native words → timed lines → plain text), stored as JSON and LRC sidecars.
- Available Canvas videos are retained for offline playback, with shared caching by Spotify track and artist-qualified album identity.
- Permanent image cache with retries for missing artwork.
- Fast-start stream resolution followed by a higher-quality stream when available.

### YouTube Music and catalog

- Official YouTube Music search restricted to verified audio tracks with an artist and album.
- Artist search with a dedicated artist page: real profile image, description, monthly listeners, albums, and tracks.
- Artist-name searches also return that artist’s songs in a separate section, not only artist cards. Song rows use the same `WEB_REMIX` client as artist/album requests and still require the row’s own official-audio watch endpoint.
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
- Animated line emphasis, lyric-view transitions, and a larger three-dot introduction: sequential illumination, growth, merge, and fade.
- Controls hide after two seconds; tapping the artwork brings them back.
- Swipe down from the lyrics header to return to the mini-player.

### Music sources and Spotify Canvas

- Built-in **Ketvoice API** source card in **Search → Sources** and **Settings → Sources**.
- Server-side search across Ultra MAX and Last Babe, shown in the app as `API` results. Retired provider routing and its Vault credential have been removed.
- Fast MP3 playback with automatic FLAC upgrade when a lossless stream is available.
- The server returns stream URLs, track IDs, and minimal track metadata. Private provider base URLs and credentials stay on the server; the client fetches audio from the returned media URL.
- Spotify Canvas lookup, Spotify authentication, and `sp_dc` handling run inside Ketvoice API. The installed app never receives the Spotify cookie.
- Canvas video is cached locally and can be limited to Wi-Fi in Settings.
- A built-in health check in **Search → Sources** verifies the function and its server-side secrets.

### Custom JSON sources

- Add an HTTPS `.json` manifest from **Search → Sources** or **Settings**.
- Supports direct manifests, `baseUrl` + relative endpoints, and BitChord-style addons.
- Search, stream, and download endpoints with placeholders such as `{query}`, `{id}`, `{quality}`, `{title}`, and `{artist}`.
- Search-result validation and a real test search before a source can be saved.
- Quality negotiation for BitChord manifests (`LOSSLESS`, `HIGH`, `LOW`, and variants such as `high`, `normal`, `low`).
- Respects `allowDownloads: 0` and prefers an explicit download endpoint when one is declared.
- See [docs/SOURCES.md](docs/SOURCES.md) for the manifest format.

### Spotify Canvas

- Real Canvas lookup through Spotify Pathfinder, resolved by Ketvoice API.
- Canvas video is cached locally and shown in the player when available.
- Automatic Canvas downloads can be restricted to Wi-Fi. Explicit song downloads also request Canvas, independently of the animated-artwork display setting, and can use mobile data.
- No Spotify `sp_dc` cookie or user setup is required. No Spotify credential is bundled with the app.

### Public publications

- Publishing is restricted to the verified **konnektsupport@gmail.com** mailbox. A matching YouTube profile only reveals the entry point; the content broker re-checks the Supabase Auth user and confirmed email on every write, so a forged client request cannot publish.
- In **2.6.1**, request a new email link and open it on the same device: Supabase redirects to `ketvoice://auth` and Ketvoice checks your access automatically. The original email link can also be pasted into the app. Older localhost links should not be reused. See [publisher login release notes](docs/releases/v2.6.1.md) for mail-client and rate-limit caveats.
- **Settings → Import a song** accepts MP3, M4A, AAC, FLAC, WAV, OGG, or Opus up to 25 MiB, an optional JPEG/PNG/WebP cover up to 4 MiB, and optional TTML/LRC lyrics up to 1 MiB.
- **Track ⋯ → Import lyrics** searches normally, previews native TTML/LRC timing, and publishes a new public lyrics version without inventing timing.
- Published songs are returned by normal search and by the Ketvoice API source, and can be played and downloaded like other tracks. Published lyrics add a selectable **Ketvoice API** provider. Auto mode may use that provider when it answers within a short window, then falls back to external providers.
- The publisher must confirm the rights/public-distribution checkbox. Published audio, covers, and lyrics are public to all users, consume the project’s storage/egress quotas, and can be removed by the project owner.
- The public media bucket has no client write policy; the broker validates signatures and sizes and performs uploads with a server-side service role.

### Settings

- Real, persisted settings for theme, accent color, launcher icon, playback quality, downloads, storage, lyrics, translation, Canvas, and YouTube Music.
- Choice rows open a sub-menu instead of silently changing a value.
- **Ketvoice API · Canvas** and **Sources** sections expose the server-side pipeline and a health test, with no cookie or token fields.
- For the authorized publisher only, a **Publications Ketvoice API** section provides song import, lyrics import, and publishing sign-out.
- Cache size and cache clearing reflect real files on the device.
- No demo account or fake “connected” state.

## Download

### 2.6.0 preview

<p align="center">
  <img src="https://github.com/Tysnnn23/Ketvoice/releases/download/v2.6.0/tablet-navigation.png" width="230" alt="Tablet navigation rail and adaptive layout" />
  <img src="https://github.com/Tysnnn23/Ketvoice/releases/download/v2.6.0/tablet-search.png" width="230" alt="Tablet search showing artist cards and that artist's songs" />
  <img src="https://github.com/Tysnnn23/Ketvoice/releases/download/v2.6.0/tablet-player.png" width="230" alt="Two-pane tablet player with artwork, controls, and lyrics" />
  <img src="https://github.com/Tysnnn23/Ketvoice/releases/download/v2.6.0/phone-search-songs.png" width="230" alt="Phone search returning songs as well as artist cards" />
</p>

Download the APK and its SHA-256 checksum from the [Releases](https://github.com/Tysnnn23/Ketvoice/releases/latest) page.

- Minimum Android version: **Android 12 (API 31)**
- Package: `com.ketvoice.app`
- Signing: Android debug certificate
- Verify the SHA-256 checksum shown in the release notes after downloading.

No Spotify cookies, add-on tokens, private add-on endpoints, or Supabase service-role keys are included in the public source or the release APK. The app contains only the public Supabase `anon` key and the function URLs. Spotify and source credentials are held in Supabase Vault; publication writes run through server-side functions.

## Build from source

### Requirements

- JDK 17 or newer
- Android SDK Platform 36 and Build Tools 36
- Android Studio or a command-line Android SDK
- Network access for Gradle dependencies

Set `ANDROID_HOME` (or create `local.properties` with `sdk.dir=...`) and run:

```bash
./gradlew assembleMobileDebug assembleQuestDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
app/build/outputs/apk/quest/debug/app-quest-debug.apk
```

On Windows:

```powershell
.\gradlew.bat assembleMobileDebug assembleQuestDebug
```

Run the JVM tests and lint with:

```bash
./gradlew testMobileDebugUnitTest testQuestDebugUnitTest lintMobileDebug lintQuestDebug
```

### Release signing

The repository has an unsigned release build type; the distributed APK is signed separately with the existing Android Debug development certificate. Add your own release keystore and signing configuration before publishing a production build. Never commit keystores, passwords, cookies, or tokens.

## Project structure

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/com/ketvoice/app/
│       │   │   ├── data/       # catalog, sources, settings, lyrics, cache, publications
│       │   │   ├── glass/      # Liquid Glass rendering
│       │   │   ├── player/     # Media3 playback and session service
│       │   │   ├── theme/      # colors and typography
│       │   │   └── ui/         # screens, player, settings, overlays, adaptive layout
│       │   ├── res/
│       │   └── AndroidManifest.xml
│       └── test/               # JVM unit tests
├── supabase/
│   ├── README.md
│   └── functions/
│       ├── ketvoice-api/       # source search, streams, and Canvas
│       └── ketvoice-content/   # restricted publishing and public reads
├── docs/
│   ├── SOURCES.md
│   ├── releases/
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
- Supabase Edge Functions (Deno) for Ketvoice API and Ketvoice Content
- JUnit 4 and org.json for JVM tests

## Status and limitations

- Validation uses JVM unit tests, Android lint, live Ketvoice API checks, a mocked publication broker harness, and an Android 16 emulator. There is no physical-device, device-farm, or Play Store validation.
- Tablet layouts are validated on an emulator at phone, portrait-tablet, and landscape-tablet window sizes. Split-screen windows follow the same constraints but were not exhaustively device-tested.
- Publishing requires the mailbox owner to complete a Supabase email login. Email delivery depends on the project’s Supabase Auth templates and rate limits; the app does not send mail automatically.
- A real authenticated upload must be confirmed by the mailbox owner. Development validated the broker with mocked storage/HTTP tests and live rejection/read checks, not by impersonating that account.
- Published media is public and storage/egress usage counts against the Supabase project’s quotas. Only the authorized mailbox can publish; there is no separate moderation queue.
- YouTube Music, lyrics, Canvas, artwork, Ketvoice API upstreams, and custom sources depend on third-party services that can change or rate-limit requests.
- A YouTube web session may be rejected or require additional verification. The app never fabricates a connected state.
- Search history cannot be read back from YouTube Music; only accepted playback events are submitted.
- Canvas availability depends on Spotify having a Canvas for that track and on a valid server-side Spotify session.
- FLAC availability is provider-dependent. Failed lossless downloads can fall back to a lower-quality playable stream; the download list reports the format actually saved.
- Ketvoice API caps a source search at eight seconds and caches results for 60 seconds. The first invocation after inactivity can still incur a function cold start.
- The public Supabase `anon` key can be extracted from any installed client. `verify_jwt` blocks anonymous HTTP calls, but it is not a complete abuse barrier.
- Custom sources that require POST requests, authentication, or a search endpoint that cannot return JSON for a test query are rejected.
- Translations are bundled for offline use. External provider errors, catalog descriptions, song names, and lyric content are not rewritten as interface text. Some translations are machine-assisted and can be improved.

## Security and privacy

See [SECURITY.md](SECURITY.md) and [PRIVACY.md](PRIVACY.md).

- No Ketvoice listener account is required. The restricted publisher signs in to Supabase Auth with the authorized mailbox; the service verifies that user and its confirmed email on every write.
- No analytics or advertising SDK is included.
- Spotify and source credentials are stored in Supabase Vault and read only by the Ketvoice API function with the service role.
- The APK contains the public Supabase `anon` key, which is not a secret.
- YouTube session data is encrypted locally with Android Keystore and is not sent to Ketvoice API.
- Custom source URLs remain on the device. Search queries and track metadata are sent to Ketvoice API and to the configured upstream providers.
- Clearing app data or uninstalling the app removes local session and cache data. Server-side function logs are retained according to the Supabase project configuration.

## Versioning and legacy builds

**2.7.0** remains the mobile/tablet release; **2.7.1** updates only Quest's landscape layout. Earlier releases and their assets remain available and are not overwritten. See [CHANGELOG.md](CHANGELOG.md) for the full release history.

Earlier development builds from **1.2.0 through 2.3.0** contained development-only Spotify session cookies and private add-on credentials in their default settings. Those binaries are intentionally not published. Version **2.3.1** was the first clean public APK. Version **2.4.0** moves the remaining source and Canvas credentials to Supabase Vault. The original **1.0.0** builds predate those credentials and are archived as-is in the legacy release; their help text contains one obsolete example add-on URL, but no session cookie or access token.

## Legal

Ketvoice is an independent project for personal use. It is not affiliated with or endorsed by Apple, Google, YouTube, Spotify, Deezer, Genius, LRCLIB, BitChord, or any other music service. All trademarks belong to their respective owners.

You are responsible for complying with the terms of service of any provider you connect and with the copyright laws that apply to you. The project does not host or distribute music. The source is published for transparency and personal experimentation; no license for redistribution is granted unless a separate license file is added by the author.

## Acknowledgements

- The Liquid Glass rendering uses [Kyant0 Backdrop](https://github.com/Kyant0/Backdrop).
- The custom-source protocol is compatible with the add-on format.
- Artwork, metadata, lyrics, and canvas data are retrieved from their respective public providers at runtime.
