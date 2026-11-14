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

<p align="center">
  <img src="ketvoice-banner.png" width="960" alt="Ketvoice — your music, beautifully connected on every device" />
</p>

## Overview

### 4.13.0 — one set of glass buttons and cover colours behind headers

Every collection now uses the same two buttons: a prominent capsule tinted with the
accent colour for the main action, and plain glass capsules for the others. In Liquid
27 they swell under the finger and settle with a small bounce. Playlist, artist and
Downloads headers glow with the colours of their cover, playlists lead with Play and
Shuffle like albums, and the droplet morph of the navigation bar is 3 % slower.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.13.0/Ketvoice-4.13.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.13.0/Ketvoice-4.13.0-Quest.apk)
· [Release notes](docs/releases/v4.13.0.md).

### 4.12.0 — skip from anywhere, a visible queue and refreshed screens

Next and Previous now work from the notification, the lock screen and headset or
Bluetooth buttons. Shuffle reorders the visible queue, Previous returns to the title
you came from, and repeat cycles through off, queue and title. The queue reorders
live, removes with a swipe and Undo, and can be cleared or saved as a playlist. A
sleep timer reaches phones, and a swipe on the mini-player or the artwork changes
title. Every list of titles shares the same swipe and long-press gestures. Replay
shows real listening time, Local music asks for its permission and gains search,
sorting and Albums/Artists views, Search gains an Albums shelf and a real Library
scope, and Explorer opens on the catalogue chart.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.12.0/Ketvoice-4.12.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.12.0/Ketvoice-4.12.0-Quest.apk)
· [Release notes](docs/releases/v4.12.0.md).

### 4.11.0 — personal Home, mixes and redesigned Downloads

Home now greets by time of day, offers shortcuts to what is on the device and varies
its secondary shelves at each launch. After ten plays it proposes up to four mixes
built from your listening (artist, similar style, on repeat, rediscover), each of
which can be saved as a playlist. Downloads gains a summary card, Titles/Albums/
Artists views, sorting and format badges.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.11.0/Ketvoice-4.11.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.11.0/Ketvoice-4.11.0-Quest.apk)
· [Release notes](docs/releases/v4.11.0.md).

### 4.10.0 — motion, droplet glass, widget and accessibility

The player artwork now grows out of the mini-player cover, Android 14+ gets
predictive back, and scrolled tabs show a compact glass title. In Liquid 27, Home,
the mini-player and Search merge and split like droplets; the glass takes a light
tint from the cover and a slow colour mesh moves behind the player. A home-screen
widget, a tabletop layout for flip-style phones, haptics and screen-reader
improvements complete the release.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.10.0/Ketvoice-4.10.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.10.0/Ketvoice-4.10.0-Quest.apk)
· [Release notes](docs/releases/v4.10.0.md).

### 4.9.0 — Liquid 27 glass, Discord callbacks and privacy fixes

A fourth glass style, **Liquid 27**, joins Clair, Dépoli and Opaque. It has
continuous corners, uniform edge refraction, defined borders instead of shadows, a
Clear ↔ Tinted slider, highlights that follow device tilt, a touch glow and a
scroll-edge fade. The Discord bridge now pumps the SDK callbacks it never received,
**Ignorer les silences** works again, track details on the website are opt-in, and
the lyrics, artwork and Canvas caches are bounded.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.9.0/Ketvoice-4.9.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.9.0/Ketvoice-4.9.0-Quest.apk)
· [Release notes](docs/releases/v4.9.0.md).

### 4.8.4 — collapsed chrome now works on 320–379 dp phones

The collapse animation was gated on a 380 dp minimum width, so many 360 dp phones
never triggered it. The threshold is now 320 dp: Home, the smaller mini-player and
Search dock in one glass row while scrolled, and the full layout returns on scroll
up. The 320 dp host test asserts the new row and 48 dp transport targets.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.4/Ketvoice-4.8.4.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.4/Ketvoice-4.8.4-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.8.4.md).

### 4.8.3 — collapsed chrome: Home, mini-player, Search

While a page is scrolled, the floating chrome becomes one liquid-glass row: Home on
the left, a smaller mini-player in the middle and Search on the right. The three-tab
capsule morphs into the Home button, the mini-player shrinks continuously with 48 dp
transport targets, and scrolling back up restores the full layout. The Discord RPC
presence and the 4.8.1 device-language behaviour are retained.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.3/Ketvoice-4.8.3.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.3/Ketvoice-4.8.3-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.8.3.md).

### 4.8.2 — Discord presence through the installed app

Discord presence is now published through the installed Discord app over RPC,
without account linking or a gateway `Ready` state. **Afficher via l'app Discord**
is the primary action, every update reports success or the SDK error, and the
activity keeps the Listening type with the track title in the status line. Account
linking remains available as an advanced option. The 4.8.1 device-language
behaviour is retained.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.2/Ketvoice-4.8.2.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.2/Ketvoice-4.8.2-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.8.2.md).

### 4.8.1 — device language and Discord recovery

Ketvoice now starts in the phone's language when it is one of the eleven supported
languages, and Settings offers a System language entry to return to that behaviour.
Discord linking no longer stays on "waiting for the Discord app": the connection has
a deadline, retries with backoff, shows the account and the presence separately, reads
the authorization code from the bridge page itself and publishes presence as
Listening. A real owner-session Discord check and physical-device validation remain
open.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.1/Ketvoice-4.8.1.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.1/Ketvoice-4.8.1-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.8.1.md).

### 4.8.0 — playback recovery and personal library

Resume a saved queue and position without automatic playback, receive search results
as sources answer, select a matching provider, adjust lyric timing per track and
preview personal backups before merging. Source priorities and lossless cache
fallback now follow the chosen playback mode. Offline DASH is limited to explicitly
authorized, unprotected supported streams; the current Lossless manifest does not
declare download permission. The release APKs include Discord support, but real
device playback and Discord authorization have not been validated.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.0/Ketvoice-4.8.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.0/Ketvoice-4.8.0-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.8.0.md).

### 4.7.0 — optical glass and collection redesign

Pressure-reactive AGSL refraction, directional edge reflections and independently
captured slider tracks accompany redesigned Library, Downloads, Album and Settings
screens. Cohesive outline icons, matte grouped content and floating glass actions
preserve the restored navigation/player arrangement and all existing audio behavior.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.7.0/Ketvoice-4.7.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.7.0/Ketvoice-4.7.0-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.7.0.md).

### 4.6.1 — lossless and Tidal playback repair

DASH/native FLAC answers are now parsed and played as DASH, including manifests
without a `.mpd` extension. Play/Pause pressed during loading keeps the requested
state, real failures are shown instead of an endless spinner, and an expired URL is
recovered once before another provider is tried. The Lossless addon is added once as
the first playback source, with a periodic keep-alive while the app process is alive.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.6.1/Ketvoice-4.6.1.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.6.1/Ketvoice-4.6.1-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.6.1.md).

### 4.6.0 — native glass and adaptive settings

A continuous glass tint updates the preview and persists after reopening. Native
AGSL remains on Android 13+; older, low-memory and reduced-motion paths have
explicit fallbacks. The previous separate navigation and cover-first player remain,
along with clean synchronized lyrics. Album, playlist, artist and Explorer details
have been refined without changing audio or provider APIs. Eleven interface
languages remain available with existing language-selection behaviour unchanged.
Device calibration and some secondary Material dialogs remain open limitations.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.6.0/Ketvoice-4.6.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.6.0/Ketvoice-4.6.0-Quest.apk)
· [Release notes and validation limits](docs/releases/v4.6.0.md).

### 3.11.0 — smoother playback and artist portraits

Readable dark action sheets, a refined charcoal theme, continuous Canvas/lyrics
control transitions and timed backing-vocal visibility. Portrait-led artist
profiles support optional public-domain signatures. Appearance adds an actual
native 120 Hz window request on supported phones, without changing resolution
or promising a measured 120 FPS. Automatic update popups retain release notes
and the release banner; Android still confirms installation.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.11.0/Ketvoice-3.11.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.11.0/Ketvoice-3.11.0-Quest.apk)
· [Release notes and validation limits](docs/releases/v3.11.0.md).

### 3.10.0 — Canvas framing fixed, AutoMix removed

Normal Canvas videos no longer inherit the artwork fade or sit underneath the
track title. AutoMix is removed from the audio engine, interface and settings;
ordinary playback, equalizer, Sonic Canvas and queue controls remain.
The website and English release notes use the canonical Ketvoice banner.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.10.0/Ketvoice-3.10.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.10.0/Ketvoice-3.10.0-Quest.apk)
· [Release notes and validation limits](docs/releases/v3.10.0.md).

### 3.9.0 — Canvas moins zoomés et finitions du lecteur

Canvas affiché entier dans le lecteur ; appui pour un mode plein écran minimal
si les proportions permettent de garder au moins 85 % de l'image. Verre des
contrôles affiné, en-têtes artiste/album retravaillés, durée totale d'album,
apparitions des paroles plus douces et traductions complétées.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.9.0/Ketvoice-3.9.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.9.0/Ketvoice-3.9.0-Quest.apk)
· [Notes et limites de validation hôte](docs/releases/v3.9.0.md).

### 3.8.0 — Verre ciblé, nouveaux réglages et recherche compacte

Les contrôles et la navigation utilisent une optique adaptée de
[PrismalAGSL](https://github.com/styropyr0/PrismalAGSL) (MIT) : réfraction du
fond réel et reflets de bord, sans appliquer le verre à toutes les cartes.
Les réglages ont un accueil par catégories et des pages dédiées ; la recherche
reste sur une ligne, même avec une grande police. Tests Android/Skia sur PC
distincts des essais physiques, non effectués pour cette version.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.8.0/Ketvoice-3.8.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.8.0/Ketvoice-3.8.0-Quest.apk)
· [Notes et limites](docs/releases/v3.8.0.md).

### 3.2.0 — Paroles cinéma, AutoMix complet et playlists premium

Les paroles gagnent un flou progressif de profondeur, un défilement plus fluide
et une intro animée ; un appui sur l'espace vide ramène les commandes. L'AutoMix
revient en version complète : double flux audio, fondu réel, analyse du rythme,
onglet dédié dans les Réglages. Les playlists adoptent un hero centré avec
glow, pochettes dans les rangées et feuille d'actions en verre (téléchargement
de playlist, export, etc.). La Ripple Android est supprimée dans toute l'app.
[Mobile](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.2.0/Ketvoice-3.2.0.apk)
· [Quest](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.2.0/Ketvoice-3.2.0-Quest.apk).
[Notes](https://github.com/Tysnnn23/Ketvoice/releases/tag/v3.2.0).

### 3.0.6 — AutoMix (BETA)

Enable AutoMix in Settings → Playback to prepare and blend consecutive songs
using two audio streams. The optional AutoMix tab adjusts the maximum crossfade
and analysis performance. Rhythm-aware beat alignment is used when analysis is
reliable; otherwise playback falls back to a simple fade. It is disabled by
default and never changes the regular player when off. This is versionCode 31
for mobile and Quest. Download the signed [mobile APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.6/Ketvoice-3.0.6.apk)
or [Quest APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.6/Ketvoice-3.0.6-Quest.apk).
See [3.0.6 release notes](docs/releases/v3.0.6.md).

### 3.0.5 — Studio sign-in returns to the browser

Open [`admin/ketvoice-admin.html`](admin/ketvoice-admin.html) locally, or use the
[hosted Studio](https://tysnnn23.github.io/Ketvoice/admin/ketvoice-admin.html).
The sign-in link requested from this page returns to the hosted Studio in the
browser. Links requested from the mobile app still return to the mobile app.
This is versionCode 30 for mobile and Quest; the APK music features remain as
in 3.0.4. Download the signed [mobile APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.5/Ketvoice-3.0.5.apk)
or [Quest APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.5/Ketvoice-3.0.5-Quest.apk).
See [3.0.5 release notes](docs/releases/v3.0.5.md).

### 3.0.4 — desktop studio and managed sources

Open [`admin/ketvoice-admin.html`](admin/ketvoice-admin.html) on a computer
to publish your own music and lyrics, edit your imported tracks and covers,
or manage Ketvoice API manifest sources. Only the verified owner account can
write; server-side checks remain mandatory. Existing installations fetch
content and sources on their next API requests. Artist headers now have an
image-derived backdrop beneath short banners. This is versionCode 29 for
mobile and Quest. Download the signed [mobile APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.4/Ketvoice-3.0.4.apk)
or [Quest APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.4/Ketvoice-3.0.4-Quest.apk).
See [3.0.4 release notes](docs/releases/v3.0.4.md).

### 3.0.3 — adaptive artist banners

Artist profiles now adapt the hero to the real image proportions. A panoramic
banner no longer ends above a large empty surface; a portrait is preferred when
available. The image fades into a sampled color behind the artist name and
controls. This update is versionCode 28 for mobile and Quest. Download the
[mobile APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.3/Ketvoice-3.0.3.apk)
or [Quest APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.0.3/Ketvoice-3.0.3-Quest.apk).
See the [3.0.3 release notes](docs/releases/v3.0.3.md) for validation limits.

Ketvoice is an independent Android music client whose visual language is inspired by Apple Music and iOS 26. On first launch the interface follows the device language when it is one of the eleven supported languages (otherwise English); Settings offers those languages plus an explicit System language entry. It is not affiliated with Apple, YouTube, Spotify or any music provider.

The latest release is **4.8.4** (`versionCode` 93) for **mobile/tablet** (Android 12+, target SDK 36) and **Quest 3/3S** (minimum SDK 32, target SDK 34, landscape-only 2D window). While a page is scrolled, the floating chrome collapses into one liquid-glass row: Home on the left, a smaller mini-player in the middle and Search on the right; scrolling back up restores the full mini-player and the three tabs. The collapse threshold now covers 320–379 dp phones, including the common 360 dp width. Discord presence through the installed app over RPC and the 4.8.1 device-language behaviour are retained. All 4.8.0 session restoration, progressive search, provider choices, lyric timing and personal backup behaviour remains. Existing glass, navigation, clean karaoke, Canvas, playlists and optional Bit-Perfect remain. Offline DASH is restricted to explicitly authorized unprotected supported streams; the current Lossless manifest does not declare permission. Eleven interface languages remain available. Discord support is included, but physical phone/Quest playback, the real Discord presence round-trip and USB bit-exact output have not been validated; see the release metadata. Group listening remains deferred.

Music-source search, stream resolution, Spotify Canvas, and published-content lookup go through two Supabase Edge Functions: **Ketvoice API** for sources/Canvas and **Ketvoice Content** for public publications. The APK contains only the project’s public Supabase `anon` key and function URLs. Spotify and source credentials remain in Supabase Vault and are never shipped with the app.

> The APK is debug-signed for personal installation and testing. It is not a Play Store release.

## Features

### Quest 3 / Quest 3S window build

- **Landscape only, with the mobile style**: original artwork/Canvas, icon controls, Liquid Glass, floating navigation, and lyric animations. In lyrics/queue mode, artwork, title, artist, and transport stay on the left; lyrics or queue fill the right. No giant text-button dashboard, tablet rail, or portrait-phone frame.
- Pointer hover light on interactive controls, optional lyric sizing, foreground keep-awake, and the sleep timer in the ordinary track ⋯ menu.
- Download **[Ketvoice-4.8.4-Quest.apk](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.4/Ketvoice-4.8.4-Quest.apk)** and follow [Quest installation instructions](docs/QUEST.md). Headset-targeted manifest and arm64 support are included; physical Quest hardware validation is still required. This is a 2D app window, not an immersive VR environment. The same-version **[mobile/tablet 4.8.4 APK](https://github.com/Tysnnn23/Ketvoice/releases/download/v4.8.4/Ketvoice-4.8.4.apk)** is available in the same release.

### Interface

- Liquid Glass controls/navigation use Kyant0 Backdrop with MIT optical primitives adapted from PrismalAGSL; content panels stay matte. Full AGSL refraction requires API 33+, with readable fallbacks on older versions and in reduced-effects modes.
- Light and dark themes with a user-selectable accent color applied across controls, progress bars, switches, and selection states.
- Home, Explore, and Library tabs, floating glass navigation, a collapsible mini-player, and animated transitions throughout.
- Adaptive layouts driven by real window constraints: a glass navigation rail at tablet widths, a reading-width Settings page, multi-column track grids, and a two-pane player with artwork/controls beside lyrics or queue. Short landscape windows keep the phone player instead of squeezing two panes.
- Artwork transitions between the full player and mini-player with a fading cover flight in both directions.
- A redesigned Library with local music, collection cards, artists, albums, recent plays, and offline downloads. Collections reflect actual listening history and downloads.
- Local playlists: create, rename, delete, reorder, remove and play; add a song from its menu. Playlists never leave the device and keep no expiring stream URLs.
- Spotify playlist import by public link: preview visible titles, confirm that the preview may be incomplete, then match them in Ketvoice. Unmatched titles stay visible and can be resolved by hand; no Spotify sign-in, cookie or audio download is used.
- Seven selectable design finishes (D1, D2, D3, D4, D5, D7, D8), each with its own switch in Settings → Appearance → Design Ketvoice.
- French, English, Hindi, Japanese, Chinese, Russian, Italian, Spanish, German, Portuguese, and Korean interface languages. Song titles, artist names, and original lyrics stay unchanged.
- Fully custom full-screen player: artwork, canvas, scrubbing, transport controls, volume, queue, lyrics, and bottom sheets.
- Adjustable launcher icon variants.
- Transparent Ketvoice logo and gradient artwork treatment without a visible image border.

### Playback and storage

- Media3/ExoPlayer playback with a foreground `MediaSessionService`.
- Single-player audio engine; no automatic crossfades or background beat analysis.
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
- **Settings → YouTube Music account → Import a song** (verified publisher only) accepts MP3, M4A, AAC, FLAC, WAV, OGG, or Opus up to 25 MiB, an optional JPEG/PNG/WebP cover up to 4 MiB, and optional TTML/LRC lyrics up to 1 MiB.
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
- Translations are bundled for offline use and hand-written for all ten non-French tables; `tools/audit_ui_texts.py --check` fails CI when a user-facing string has no bundled translation. External provider errors, catalog descriptions, song names, and lyric content are not rewritten as interface text.

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
