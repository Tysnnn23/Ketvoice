<h1 align="center">🎧 KETVOICE — RELEASE HISTORY</h1>

<p align="center">
  <strong>Every public version, explained as a before → after story.</strong><br>
  Eleven interface languages, French by default. These release notes are in English.
</p>

---

# Ketvoice 3.5.0

![Ketvoice 3.5.0](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.5.0/ketvoice-banner.png)

**versionCode 42** — installs over 3.4.3 and every earlier build (same signing certificate).

## Lyrics

- **Before:** a fully blurred copy of the active line appeared above the lyrics. **After:** one sharp layer per line, with the usual progressive fade on neighbouring lines.
- **Before:** unselected lines looked "basic" and the text jumped when the active line changed. **After:** every line shares the same size and weight; only the colour marks the active line.
- **Before:** French credits ("Paroles : …", "Compositeur : …") and duplicated lines showed up as ghost lyrics. **After:** they are filtered out.

## Artist page

- **Before:** portrait photos were squeezed into a fixed banner and faces were cropped. **After:** the header follows the real aspect ratio (320–460 dp), anchored to the top, and photos fade in.
- Similar artists without a photo show the artist's initial.

## Interface

- Player controls use a firm press spring with a small bounce on release (instant with "reduce motion").
- Official Spotify icon in the Spotify import.

## In-app updates

- **Before:** `3.5.0` and `3.5.0-BETA` compared as equal, so beta testers never received the final release. **After:** a stable build ranks above its own pre-release (covered by 7 unit tests).
- The check looks at the 10 latest releases (stable and beta); "Later" keeps a green badge next to the profile avatar.
- Only the APK matching your build (phone or Quest) is offered.

> Versions 3.3.1 to 3.4.3 are documented in their GitHub release notes.

---

# Ketvoice 3.3.0

**versionCode 35** — installs over v3.2.0 and every previous release (same signing certificate).

## 🎨 Reference design pass (Liquid Glass + Apple Music-style pages)

This release completes the interface alignment with the provided design references:

- **Navigation dock**: the liquid-glass bottom bar keeps its sliding selection capsule and long-press lens drag, with the specular mini-player above it. The selected capsule now reads as a lighter glass pill, closer to the reference.
- **Library, immersive top**: "VOTRE EXPÉRIENCE D'ÉCOUTE" dark stat card and "VOTRE ARTISTE DU MOMENT" card, system shortcut rows (Téléchargements, Musique locale) and the playlist card row (+ / Spotify import / playlists) — all matching the reference layout.
  - **All statistics are now real**: listening minutes come from actual play counts weighted by track duration, play totals from the listening history, the artist card only appears when a top artist exists, and "MEMBRE DEPUIS" reflects the app's first launch. The placeholder values (forced minimums, hardcoded artist and date) are gone.
- **Explorer, category cards**: rebuilt in the reference style — a solid tinted band on the left carrying the title, and a desaturated artwork fused into the card color on the right. Each genre keeps a stable vivid tint across sessions.

## 🧭 Fixes
- Old **"KONNEKT" branding replaced by "KETVOICE"** wherever it referred to the app.
- The Téléchargements and Musique locale shortcuts in the Library opened a broken empty catalog view; they now open the real Downloads and Local music screens.
- New interface labels translated across the 10 non-French catalogs (809 messages per language).

## 📦 Install
- `Ketvoice-3.3.0.apk` — phones and tablets
- `Ketvoice-3.3.0-Quest.apk` — Meta Quest (landscape 2D window, mobile style)

## ✅ Validation
- Unit tests pass on both flavors; lint 0 errors on both flavors.
- Release APKs verified: `apksigner` v2 (certificate SHA-256 `ae25a19c…`), 16 KB page alignment, `aapt` badging (versionCode 35).
- Visual comparisons with the reference video were done on the rendered frames; no physical-device or emulator run is claimed for this build.


# Ketvoice 3.2.0

**versionCode 34** — installs over v3.1.0 and every previous release (same signing certificate).

## 🎤 Lyrics, cinema mode
- **Progressive depth blur**: upcoming lines sharpen toward the active line, then soften again the further down they go (subtle at 1–2 lines, deep at 5+) — the sung lines keep their light haze.
- Smoother follow-scroll (0.56 s eased glide instead of a short snap).
- The three intro dots before the first line are now animated: each dot breathes on its own phase, lights up with a soft bounce and a faint halo as the music approaches.
- Tapping empty space in the lyrics view brings the transport controls back instantly.

## 🎛️ AutoMix, fully integrated
- The crossfade engine returns as a complete feature: a second audio deck in the playback service, real equal-power/linear crossfades, and optional rhythm analysis (BPM, beat alignment, vocal-risk and quiet-tail detection) to place the transition where it sounds natural.
- Arms the next track in the background, starts the blend a beat before the end, and falls back to a clean fade when analysis is unavailable. Manual skip triggers a short tasteful crossfade.
- New toggle in Réglages → Lecture, with an **AutoMix tab**: maximum fade duration (automatic or 1–30 s custom) and analysis performance level (Basse/Moyenne/Standard/Élevée).
- It is disabled by default and never changes the regular player when off. A discreet overlay shows the blend progress during a transition.

## 🎵 Playlists, rebuilt again — this time premium
- Centered hero: large artwork with a soft glow halo, bold name, availability line.
- Track rows now show artwork, bolder titles, and the same typography as the player.
- The ⋯ button opens a **glass action sheet in the player-menu style**: Tout lire ensuite, **Télécharger la playlist** (with pending count), Renommer, Exporter (.json), Exporter (.m3u), Supprimer la playlist.

## ✨ Interface polish
- **The Android ripple feedback is gone app-wide**: interactions now live in the glass, the content and the accent colors only — consistent with the player and home style.
- All new interface strings are translated across the 11 interface languages (804 messages per language).

## 📦 Install
- `Ketvoice-3.2.0.apk` — phones and tablets
- `Ketvoice-3.2.0-Quest.apk` — Meta Quest (landscape 2D window, mobile style)

## ✅ Validation
- Unit tests pass on both flavors, including the AutoMix planner/DSP test suite; lint 0 errors on both flavors.
- Release APKs verified: `apksigner` v2 (certificate SHA-256 `ae25a19c…`), 16 KB page alignment, `aapt` badging (versionCode 34).
- Crossfade audio behavior is exercised by mocked unit tests only; no physical-device listening validation is claimed. Backend unchanged in this release.


# Ketvoice 3.1.0

**versionCode 33** — installs over both the 3.0.x line (v3.0.5–v3.0.7) and the 3.0.0-Beta line. Same signing certificate as every previous release; no uninstall needed.

This release reunifies the two parallel lines: the playlist/navigation/Replay work from 3.0.0-Beta4 and the later 3.0.x maintenance are combined in a single Android codebase. The iOS port directory and its build workflow are removed from the repository.

## 🎵 Playlists, redesigned
- Complete playlist screen redesign: large artwork hero header, bold title, filled play button, overflow menu (rename, export, delete).
- **Drag & drop reordering** with a drag handle on every row and animated live reordering.
- **Export** as `.json` (re-importable) or `.m3u` through the Android share sheet; **import** a `.json` or `.m3u` file from the Library.
- Playlists open on top of the library with a proper back stack.

## 🧭 Navigation
- **Overlay back stack**: artist → similar artist → back, album → artist → back; Settings → Downloads/Sources → back returns to Settings.
- Differentiated transitions: horizontal push forward, reverse on back, vertical fade for tab changes.

## 🔁 Replay
- Real Replay screen: total listens, most-played titles with per-track play counts, top artists — counted from actual playback.

## 🎤 Lyrics
- **Source diagnostics** panel (Réglages → Paroles): tests every lyrics provider live on the current track.

## 🐛 Fixes
- Tablet player: interior horizontal drags (scrub, volume) no longer move the whole screen; swipe-to-close only responds from the screen edges.
- `previous` wraps to the last title at the top of the queue; shuffle avoids repeats.
- Playback retry after stream errors is state-based; possible double download during quality upgrade fixed.
- Download deletion offers **Annuler**; YouTube Music sign-out asks for confirmation.

## 🔒 Backend (Supabase)
- `ketvoice-api` now enforces **per-IP rate limits** (search 60/min, stream 120/min, canvas 30/min) and caches search results in Postgres for 6 hours. Both verified live: the 61st request returns HTTP 429; repeat searches are served from the cache.

## ℹ️ Note for users coming from 3.0.6/3.0.7
The experimental **AutoMix** crossfade feature is not carried into this unified build — its player hooks were incompatible with the unified playback engine and shipping it half-wired was not acceptable. It remains available in the archived v3.0.6/v3.0.7 releases, which stay published. All other 3.0.x fixes are included.

## 📦 Install
- `Ketvoice-3.1.0.apk` — phones and tablets
- `Ketvoice-3.1.0-Quest.apk` — Meta Quest (landscape 2D window, mobile style)

## ✅ Validation
- Unit tests pass on both flavors (mobile + Quest); lint 0 errors on both flavors.
- Release APKs verified: `apksigner` (v2 scheme, certificate SHA-256 `ae25a19c…`), `zipalign -c -P 16` (4 KB and 16 KB page alignment), `aapt` badging (versionCode 33).
- Emulator install checks were not run for this build (no AVD available in the build environment). The install failure reported on 3.0.0-Beta4 was a versionCode downgrade (26 over an installed 32), not a package defect.


# Ketvoice 3.0.0-Beta4

## 🎵 Playlists, redesigned
- Complete playlist screen redesign: large artwork hero header, bold title, filled play button, and a proper overflow menu (rename, export, delete).
- **Drag & drop reordering**: edit mode now shows a drag handle on every row — grab ⠿ and slide a title to its new place, with animated live reordering. No more up/down arrow buttons.
- **Export a playlist** as `.json` (full fidelity, re-importable) or `.m3u` (universal), through the Android share sheet.
- **Import a playlist file** from the Library: pick a Ketvoice `.json` or any `.m3u` file; titles are added and can be resolved to streams afterwards, exactly like Spotify import previews.
- Playlists now open on top of the library with a proper back stack — the back button and system back both return where you came from.

## 🧭 Navigation
- **Overlay back stack**: opening an artist from a similar artist, or an album from an artist page, now keeps the previous screen — back returns through the chain instead of dumping you at the root.
- Differentiated transitions: horizontal push forward, reverse on back, vertical fade for tab changes.

## 🔁 Replay
- Replay is now a real screen: total listens, most-played titles (with per-track play counts) and your top artists, all counted from actual playback.
- New Replay card in the Library collections.

## 🎤 Lyrics
- New **source diagnostics** panel (Réglages → Paroles): tests every lyrics provider live on the current track and reports what each one can offer (native syllables, word timing, line timing or plain text).

## 🐛 Fixes
- Fixed tablet player: horizontal swipes inside the player (scrub bar, volume) no longer drag the whole screen; swipe-to-close now only responds from the screen edges.
- Fixed `previous` button: at the top of the queue it now wraps to the last title instead of restarting the first one.
- Shuffle no longer repeats the same title back-to-back and avoids recently played tracks.
- Playback retry after a stream error is now state-based, not string-based (the retry button could silently stop working when the quality label changed).
- Fixed a possible double download when quality upgrade warm-up and background caching raced on the same stream.
- Deleting a download now shows an **Annuler** action for a few seconds (relaunches the download).
- Sign-out from YouTube Music now asks for confirmation.

## 🔒 Backend (Supabase)
- The public `ketvoice-api` proxy now enforces **per-IP rate limits** on search/stream/canvas (memory + database-backed rolling window), protecting egress and the server-side provider cookies.
- Search results are now **cached server-side** (6 h) in Postgres: faster repeat searches, less load on upstream providers.
- Requires applying the two new migrations (`20261026120000_api_rate_limit.sql`, `20261026120100_search_cache.sql`) and redeploying the function.

## 📦 Install
- `Ketvoice-3.0.0-Beta4.apk` — phones
- `Ketvoice-3.0.0-Beta4-Quest.apk` — Meta Quest (landscape 2D window, same interface)

## ✅ Validation
- 127 JVM unit tests pass on the mobile flavor (122 existing + 5 new playlist export/import tests); the Quest flavor unit tests pass as well.
- `compileMobileDebugKotlin` and `compileQuestDebugKotlin` succeed.
- Lint: 0 errors on both flavors (121 warnings, 8 hints — unchanged baseline level).
- Emulator-based Compose UI tests were not run for this build. No live provider download, no physical Quest run and no real-owner publication edit is claimed.

---

## 3.0.7 — NAVIGATION BACK HISTORY (versionCode 32)

- Hardware Back and each in-app back button now restore the previous screen,
  including artist, album, playlist, settings, downloads and search routes.
- Opening an artist from the full player restores the player when returning.
- Switching main tabs intentionally clears navigation history.
- See [3.0.7 release notes](docs/releases/v3.0.7.md).

---

## 3.0.6 — AUTOMIX (BETA) (versionCode 31)

- Optional AutoMix blends the current and upcoming songs using two independent
  Media3 audio decks and a real equal-power crossfade. It is off by default;
  turning it off cancels any ongoing blend immediately.
- Off-UI-thread PCM analysis estimates BPM, energy and likely vocal density near
  transition points. Compatible tempos align beat phases without time-stretching;
  failed or unavailable analyses fall back to a simple fade.
- Settings add an AutoMix tab with automatic/custom maximum fade (1–30 seconds)
  and four performance levels, reduced on modest devices. The player shows a
  small two-cover progress indicator while tracks overlap.
- See [3.0.6 release notes](docs/releases/v3.0.6.md) for validation limits.

---

## 3.0.5 — STUDIO EMAIL RETURN (versionCode 30)

- A sign-in link requested from the desktop Studio now returns to the hosted
  Studio page and signs in there automatically.
- The mobile app continues to request its own `ketvoice://auth` redirect;
  its e-mail link flow is unchanged.
- The Studio still opens from its local HTML shortcut. No changes to music
  playback, Quest layout or the authorization of publisher actions.
- See [3.0.5 release notes](docs/releases/v3.0.5.md).

---

## 3.0.4 — STUDIO, MANAGED SOURCES AND ARTIST BACKDROP (versionCode 29)

- Add a local desktop HTML studio for owner-authenticated publishing, imported
  song edits, cover replacement, lyrics and managed Ketvoice API sources.
- The API reloads active managed sources on searches and stream requests;
  deleting, disabling or changing a source needs no APK installation or alert.
- Artist pages extend the image into a blurred backdrop beneath the banner,
  avoiding a blank block when only a short landscape image is available.
- See [3.0.4 release notes](docs/releases/v3.0.4.md).

---

## 3.0.3 — ADAPTIVE ARTIST BANNERS (versionCode 28)

- Artist headers now adapt to the source image's measured aspect ratio. Short
  panoramic banners no longer leave an oversized empty color block beneath them.
- The image-to-color fade starts at the actual lower edge of the displayed
  artwork. When a portrait source exists, it takes priority over a wide banner.
- See [3.0.3 release notes](docs/releases/v3.0.3.md) for validation limits.

---

## 3.0.2 — ARTIST IMAGE FRAMING (versionCode 27)

- Artist profiles now prefer a wide header image when the catalog provides one.
  Square portraits keep their natural width instead of being enlarged to cover
  the entire page, eliminating the excessive crop.
- A taller, full-screen-inspired header fades into a sampled pastel tone, with
  the artist name and functional shuffle/play/share controls on that surface.
- See [3.0.2 release notes](docs/releases/v3.0.2.md) for validation limits.

---

## 3.0.1 — IMMERSIVE ARTIST PROFILES (versionCode 26)

- Artist pages now open with a large, unblurred portrait and a gradual blend into
  a color sampled from the artwork; playback and refresh actions sit in the header.
- Artist biographies are translated to the selected app language, with the
  original text retained if translation is unavailable.
- Tapping an artist in the full-screen player now reveals the profile and keeps
  audio playing in the mini-player.
- See [3.0.1 release notes](docs/releases/v3.0.1.md). Existing releases remain intact.

---

## 3.0.0-Beta3 — SIGNED APK REBUILD (versionCode 25)

- Rebuilt the latest Beta3 source, retaining the recent beta UI and crash fixes.
- The original mobile and Quest APKs had no valid Android signature. Release
  outputs are now automatically signed, and a blocking verification gate checks
  certificate, version, ZIP CRC, 16 KB alignment and downloaded release hashes.
- Protected captured screens from sampling their own glass backdrop; preserved
  Liquid Glass on sibling chrome. Hardened catalog/artist error handling.
- Fixed local-audio permission requests for Android 12/12L and 13+, denied/read
  error states, true 2×2 cover mosaics and bottom navigation clearance.
- 117 JVM and 19 Compose tests pass per flavor, plus 8 packaging-gate unit tests.
  Both signed release APKs install and launch on an emulator. No physical-device,
  real owner-session or full live provider playback validation is claimed.
- [Rebuild notes](docs/releases/v3.0.0-Beta3-rebuild.1.md). Separate prerelease tag;
  the original Beta3 and all previous releases remain intact.

---


## 2.12.0 — PLAYLISTS, SPOTIFY IMPORT & SELECTED DESIGN FINISHES

**Mobile, tablet and Quest · versionCode 21**

- Library gains **Créer une playlist** and **Importer depuis Spotify**. Playlists are local only: create, rename, delete, reorder, remove and play, with an atomic bounded store and no expiring stream URLs kept on disk.
- Spotify import reads a public playlist preview by link, warns that the preview may be incomplete, requires confirmation, and lists matched and unmatched titles separately. No Spotify login, cookie, audio or 30-second preview download is used. Unmatched titles stay visible and can be resolved manually.
- The import cover is an original Ketvoice artwork tinted by the playlist and may use the public artwork URL as a dimmed background.
- Selected designs **D1, D2, D3, D4, D5, D7 and D8** are implemented, each with its own switch in Réglages → Design Ketvoice. D6 was not implemented. Quest remains a 2D landscape window.
- The whole interface is audited and translated: 745 messages per language across the 10 non-French catalogs, placeholder parity checked, and the selected language now also applies to native Android dialogs.
- 117 JVM tests and 15 Android Compose tests pass per flavor; lint and release results are recorded in `validation.json`.
- No real Spotify account, Spotify audio download, authenticated owner edit or physical Quest run is claimed. See [2.12.0 release notes](docs/releases/v2.12.0.md).

---

## 2.11.0 — IMPORTED MEDIA EDITS, SONIC CANVAS & OFFLINE CAPSULES

**Mobile, tablet and Quest · versionCode 20**

- The verified publisher can replace audio and/or artwork only on its own imported publications. Ownership, file validation and concurrent-edit protection are enforced server-side; lyrics and track IDs are preserved.
- Imported stream cache keys include the immutable media revision URL. Existing offline downloads are never silently replaced.
- A global video Canvas switch disables video playback and future video downloads. Sonic Canvas is separate and off by default, reacting to local decoded PCM without using the microphone.
- Offline capsules provide a duration/budget selection preview, favorite-first planning and sequential downloads without evicting existing files. Readiness comes from saved files; shared caches are outside the capsule budget.
- Ketogether remains deferred; existing single-device Wi-Fi handoff is unchanged.
- 110 JVM tests and 12 Android Compose tests pass per flavor; lint 0 errors, 137 warnings, 8 hints. Broker checks: 21 existing and 23 edit cases mocked; 3 live read-only checks. Content broker v4 deployed with JWT verification on.
- No authenticated real-owner edit, full live capsule download or physical Quest test is claimed. See [2.11.0 release notes](docs/releases/v2.11.0.md).

---

## 2.10.0 — ALBUM COLOURS, ARTWORK DEPTH & LIBRARY MOTION

**Mobile, tablet and Quest · versionCode 19**

- Album-derived player accents with contrast adjustment and an opt-out setting.
- Subtle artwork depth that leaves gestures intact, one-time collection entrances, locale-aware animated album sorting and real per-track download progress/success feedback.
- Longer titles, actionable empty states and clearer Quest detail text; landscape split panes and the 2.9.0 artist pages remain intact.
- A5 (playlist-add animation) is explicitly deferred; no new playlist feature is introduced.
- 98 JVM tests and 7 Android Compose tests pass per flavor. Lint has 0 errors (129 warnings, 8 hints). Both APKs retain the existing development signing certificate.
- Device UI checks use owned-metadata fixtures on an Android 16 emulator, not physical Quest hardware or a live provider download. Previous releases and the backend are unchanged.

See [2.10.0 release notes](docs/releases/v2.10.0.md) and [implementation/validation details](docs/SELECTED-POLISH.md).

## 2.9.0 — ARTIST PAGES & PLAYER READABILITY

- Full-width blurred artist banners with gradient overlays and larger artist headings.
- Larger player titles and clickable artist names when a catalog artist ID is available.
- See the original [2.9.0 change notes](CHANGES-2.9.0.md).

---

## 2.8.0 — GLASS, MOTION, EQUALIZER & SAME-WIFI HANDOFF

**Mobile, tablet and Quest:** 23 October 2026 · **versionCode:** 17

- Liquid Glass surfaces get tighter bevel/refraction and a soft gradient rim; Quest hover light follows the pointer.
- One motion vocabulary with an **Animations réduites** toggle and Android's animator scale respected. The three intro dots are now fixed-size lights that switch on in sequence — no halo, circle, merge, or zoom — in a reserved slot so lyrics do not jump.
- Lyrics stay visible during the intro, follow correctly, pause following on manual scroll, and resume after a seek. Karaoke masks are built once per layout instead of every frame.
- Equalizer with five bands, presets, and ±12 dB controls, applied to the real playback audio session through the device's native effect and clamped to its supported range. Unsupported devices are told so.
- Same-Wi-Fi transfer with local mDNS discovery and **mandatory confirmation on the receiver**: current track, position, flags, and up to 50 catalog queue entries. No QR code, account, cookies, tokens, files, or private URLs.
- Lyrics size now applies on phones/tablets; optional panoramic Canvas is off by default; favorite and transport polish; stable queue row keys.
- 87 JVM tests pass per flavor; lint 0 errors. Signed APKs checked on an Android 16 emulator: phone player, Quest landscape player, native equalizer effect, mDNS discovery, and a real LAN offer with explicit refusal. No physical Quest or real owner upload test.
- The 2.7.0 server-side music-upload fix is unchanged; no backend change in this release.

See [2.8.0 notes](docs/releases/v2.8.0.md), [Quest guide](docs/QUEST.md), and [2.7.1 Quest notes](docs/releases/v2.7.1.md). Previous releases are preserved.

---

## 2.7.1 — QUEST LANDSCAPE: ARTWORK LEFT, LYRICS RIGHT

**Quest-only release:** 22 October 2026 · **versionCode:** 16

- Both Quest activities request landscape; default 1280×800 dp window with aspect-ratio locking. Removed the portrait-phone frame.
- Lyrics and queue use the right pane. Artwork/Canvas, title, artist, and original mobile playback/volume controls remain left and no longer disappear after the phone's two-second lyric delay.
- Normal artwork mode retains cover left and controls right. Mobile icons, animations, karaoke, and styling are reused without the oversized Quest toolbar.
- 68 tests pass per flavor; Quest build/lint/signature checks pass. Signed APK UI checks run on an Android emulator at 1280×800 and 960×600 dp. Physical Quest validation remains outstanding.
- The mobile 2.7.0 APK, backend, and previous release assets are unchanged. No new mobile APK is published.

See [Quest 2.7.1 notes](docs/releases/v2.7.1.md).

---

## 2.7.0 — MOBILE-STYLE QUEST WINDOW & MUSIC UPLOAD FIX

**Released:** 22 October 2026 · **versionCode:** 15

- Separate Quest 3/3S APK that reuses the mobile interface, player, gestures, floating navigation, Liquid Glass, and animations. No replacement Quest dashboard, oversized toolbar, tablet rail, or forced two-pane player.
- Portrait window defaults and bounded phone-style content on wide windows. Optional lyric sizing/keep-awake in Settings and a sleep timer in the existing track menu.
- Fixed music upload rollback caused by parsing an empty successful PostgREST 201 response after saving associated lyrics. Deployed server-side; existing clients benefit too. Server authorization and validation stay intact.
- 66 JVM tests pass per flavor; both release builds and lint pass with 0 errors; 21 mocked broker checks pass. Signed Quest APK installation/UI/timer checks use an Android emulator. No physical Quest compatibility/FPS guarantee or real owner upload is claimed.

See [2.7.0 notes](docs/releases/v2.7.0.md) and [Quest guide](docs/QUEST.md). Previous published releases are preserved.

---

## 🔗 2.6.1 — PUBLISHER LOGIN RETURNS TO KETVOICE

**Released:** 22 October 2026<br>
**Version:** `2.6.1` (`versionCode` 14)<br>
**Status:** Login hotfix; 2.6.0 and 2.5.0 remain unchanged

- **Before:** opening the email link ended at localhost; pasting a standard Supabase link also used the wrong verification payload.
- **After:** new email links return to `ketvoice://auth`, reopening the publisher screen in a cold or already-running app. The original email link is verified using `token_hash` when pasted manually.
- Callback sessions are checked against Supabase and the publication broker; only the confirmed publisher mailbox can publish. No client-side permission bypass, SMTP credential, or paid subscription is added.
- Numeric codes remain supported, but the selected delivery workflow is a direct email link, not a customized code-only email.
- 61 JVM tests pass; lint has 0 errors; 17 mocked publication API checks and 5 emulator callback checks pass. The mailbox owner must still complete the real email-login test.

See [2.6.1 notes](docs/releases/v2.6.1.md) for installation, checksums, and limitations. Request a **new** email link after installing.

---

## 📱 2.6.0 — TABLETS, RICHER ARTIST SEARCH & PUBLIC PUBLICATIONS

**Released:** 22 October 2026<br>
**Version:** `2.6.0` (`versionCode` 13)<br>
**Status:** Final release; 2.5.0 remains published and is not overwritten

### Before → after

| Area | Before | After |
|---|---|---|
| **Tablet navigation** | Phone navigation stretched across large windows. | A floating glass rail appears at tablet widths, with a compact labelled rail for portrait tablets and an expanded rail on wide landscapes. |
| **Tablet layouts** | Lists, grids, settings, and dialogs used phone widths. | Track grids gain columns, Explore tiles adapt, Settings/import forms keep readable widths, and mini-player/dialog bounds stop stretching. |
| **Tablet player** | One vertically stretched player column. | Artwork and controls sit beside lyrics or queue when the window has room. Short landscape windows such as phones keep the single-pane landscape player instead of squeezing two panes. |
| **Artist search** | Searching an artist name showed artist cards, but the strict song parser discarded valid rows. | Artist searches now show the artist’s songs in a separate section as well as the artist card. Requests use the same `WEB_REMIX` client as artist/album lookups, and rows still need their own official-audio watch endpoint. |
| **Search chrome** | On phones, the floating mini-player and navigation could cover results while the keyboard was open. | Floating chrome hides while the IME is visible and returns when the keyboard closes. |
| **Public songs** | Every playable track came from external sources. | Authorized publications appear in normal search and the Ketvoice API source, with public streaming, downloads, artwork, and lyrics. |
| **Public lyrics** | Lyrics came only from external providers. | A published TTML/LRC version adds a selectable **Ketvoice API** lyric provider. Auto mode may use it when it answers within a short window, then falls back to the existing providers. Original timing is preserved. |
| **Publishing access** | No in-app import workflow. | Only the verified **konnektsupport@gmail.com** mailbox can publish. A matching YouTube profile reveals the entry point, but the broker re-verifies the Supabase Auth user and confirmed email on every write. |
| **Import workflow** | Not available. | **Settings → Import a song** accepts MP3/M4A/AAC/FLAC/WAV/OGG/Opus up to 25 MiB, an optional cover, and optional TTML/LRC. **Track ⋯ → Import lyrics** previews native timing before publishing a supplementary public version. |
| **Write security** | Not applicable. | No client write grants on the public tables or media bucket. The broker validates files/signatures/sizes, rejects unauthenticated and forged requests before consuming bodies, and uses a server-side service role. The YouTube session is never sent to the content service. |

### Validation and limitations

- JVM tests, Android lint, debug/release builds, signing checks, and credential scans are part of the release checks.
- Android 16 emulator: phone portrait, phone landscape, tablet portrait, and 1280×800 dp landscape windows; artist searches returning real songs; keyboard-safe search; adaptive settings/import/player screens.
- A mocked HTTP/Storage harness exercises the publication broker and authorization paths. Live checks confirm public reads, rejected unauthorized/forged writes, database grants/RLS, and security advisories.
- A real authenticated upload requires the mailbox owner to open the Supabase email link. Development did not impersonate that account or send mail automatically.
- Published media is public and counts against Supabase storage/egress quotas. There is no separate moderation queue beyond the single authorized publisher.
- Tablet validation is emulator-only; no physical tablet or device farm was available.
- No historical 1.2.0–2.3.0 APKs are published, and the public 2.5.0 release remains unchanged.

---

## 🚀 2.5.0 — OFFLINE MUSIC, LANGUAGES & MOTION

**Released:** 21 October 2026<br>
**Version:** `2.5.0` (`versionCode` 12)<br>
**Status:** Final release; supersedes the unpublished 2.4.0 candidate

### Before → after

| Area | Before | After |
|---|---|---|
| **Languages** | French-only interface. | French, English, Hindi, Japanese, Chinese, Russian, Italian, Spanish, German, Portuguese, and Korean throughout the interface, persisted in Settings and available offline. Original songs, artists, and lyric text remain unchanged. |
| **Downloads** | A single failed stream could prevent a usable offline song. | Redirect handling, retry/resume, audio signature and response-length validation, selected-quality preference, and playable fallbacks. The saved format is shown honestly. |
| **Offline lyrics** | Lyrics were fetched independently of the audio download. | The best available native syllable, word, line, or plain lyrics are stored with the song as JSON and LRC sidecars, then loaded locally before network requests. No timings are fabricated. |
| **Canvas** | Playback cache only. | Explicit song downloads also retain available Canvas media. Persistent Spotify-ID and artist-qualified album mappings allow reuse. |
| **Library** | Simple collection rows. | Local-music card, horizontal collection cards, artist circles, albums, recent tracks, and downloads, backed by actual library data. |
| **Player motion** | Full-screen dismissal without a cover handoff. | Fading artwork flight between the full player and mini-player, in both directions. |
| **Lyric motion** | Abrupt line emphasis and small looping dots. | Animated line color/blur/scale, lyric-view opening and closing, and larger dots that illuminate sequentially, grow, merge, and fade before lyrics. |
| **Providers** | A third provider could stall or rate-limit requests. | Only Ultra MAX and Last Babe remain in built-in routing; the retired provider's server configuration and Vault secret are deleted. |
| **Credentials** | Server migration prepared as 2.4.0. | That isolation is included here: Spotify/add-on credentials stay in Supabase Vault, never in the APK. |

### Validation and limitations

- JVM tests, Android lint, debug/release builds, APK signing/integrity checks, and credential scans are part of the release checks.
- Android 16 emulator: real source search, playback, downloaded audio/lyric sidecars/Canvas, and language switching across screens.
- A provider may not offer lyrics, Canvas, or the selected audio quality. Optional asset failures do not discard saved audio. Lower-quality fallback is possible.
- Interface translations are machine-assisted with curated corrections; external provider content is left intact. Physical-device validation is not available.
- Explicit downloads may fetch Canvas over mobile data even when automatic artwork downloads are Wi-Fi-only.
- No 2.4.0 APK or credential-bearing historical APKs are published with this release.

---

## 🧪 2.4.0 — KETVOICE API (SUPERSEDED CANDIDATE)

**Released:** 21 October 2026<br>
**Version:** `2.4.0` (`versionCode` 11)<br>
**Status:** Unpublished candidate — included in and superseded by 2.5.0

Version 2.4.0 moves the remaining sensitive music-source and Spotify Canvas work to a Supabase Edge Function called **Ketvoice API**. The app keeps its French Liquid Glass interface and its real playback pipeline, but it no longer asks the user to paste a Spotify cookie or private add-on URLs.

### 🔐 Credentials — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Spotify Canvas** | The user had to find and paste a personal `sp_dc` cookie into Settings. | Ketvoice API resolves Canvas server-side. The app has no Spotify cookie field and receives only a Canvas URL and Spotify ID. |
| **Source search and streams** | Add-on base URLs and tokens were user-supplied app settings. | Ketvoice API searches the configured providers and returns only track metadata and short-lived stream URLs. |
| **APK contents** | No credentials were bundled, but users still handled sensitive values. | The APK contains only the public Supabase `anon` key and the function URL. Spotify and add-on credentials live in Supabase Vault. |
| **Server permissions** | No Ketvoice backend existed. | The function uses the Supabase service role only to read named Vault secrets. The helper RPC is service-role-only. |

### 🎵 Sources and Canvas — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Source UI** | Users added JSON manifests and configured private add-ons manually. | A pinned **Ketvoice API** card appears in **Search → Sources** and Settings, with a health test. |
| **Source results** | Results came from user-configured endpoints. | Server-side results are labelled `API` in the app. The final 2.5.0 provider set is Ultra MAX and Last Babe. |
| **Playback quality** | The app resolved add-on streams directly. | Ketvoice API resolves a fast MP3 start and a FLAC upgrade when a lossless provider has the track. |
| **Canvas setup** | Manual Spotify cookie entry. | No setup. Canvas works when Spotify has a Canvas for the track and the server session is valid. |
| **Provider failures** | A slow or failing add-on could delay the whole search. | Each source search has an eight-second cap, results are cached for 60 seconds, and HTTP `429` puts a provider on a five-minute cooldown. |

### ✅ Validation — before → after

| Check | 😕 Before | ✅ After |
|---|---|---|
| **Backend** | No server-side broker. | Live `/health`, `/search`, `/stream`, and `/canvas` checks pass. Ultra MAX returns MP3, Last Babe returns FLAC, and Canvas returns a real `canvaz.scdn.co` URL. |
| **Android build** | 2.3.1 only. | `compileDebugKotlin`, `assembleDebug`, `assembleRelease`, JVM tests, and `lintDebug` pass for 2.4.0. |
| **APK scan** | No credentials in 2.3.1. | No `sp_dc` value, long Spotify cookie pattern, or private add-on host in the signed 2.4.0 APK. |
| **Runtime** | No 2.4.0 run. | Android 16 emulator: search shows `API` results, playback reaches `PLAYING`, and Canvas downloads to the app cache. |
| **Settings** | Cookie and add-on fields were visible. | Canvas and Sources are read-only Ketvoice API sections; the app shows version 2.4.0 and a server health test. |

### ⚠️ Known limitations

- Providers can rate-limit requests. Ketvoice API temporarily skips unavailable providers and continues with available sources.
- Runtime validation used an Android 16 emulator, not a physical device.
- The public Supabase `anon` key can be extracted from any client. `verify_jwt` prevents unauthenticated calls, but it is not a full abuse barrier.
- Custom JSON sources remain user-managed and are separate from Ketvoice API.

---

## 🚀 2.3.1 — THE CLEAN PUBLIC BUILD

**Released:** 21 October 2026  
**Version:** `2.3.1` (`versionCode` 10)  
**Status:** ✅ Previous clean public release

This release is intentionally small in code and huge in safety. It removes the personal credentials that made the earlier APKs unsafe to share.

### 🔐 Security cleanup — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Spotify Canvas** | A personal Spotify `sp_dc` session cookie was compiled into the default settings. Anyone with the APK could extract it. | The default is empty. Each user enters their own cookie in **Settings → Spotify Canvas**. |
| **Private add-ons** | Private add-on URLs and tokens were compiled into `AppSettings`. | All default add-on endpoints are empty. Users add their own trusted sources in Settings or **Search → Sources**. |
| **Blank add-ons** | A blank add-on base could still start a malformed request before failing. | Blank endpoints are filtered out. Search and stream resolution return safely with no request. |
| **Public distribution** | The app could not be published safely with its embedded credentials. | The source and the 2.3.1 APK contain no session cookies, API tokens, or private add-on credentials. |
| **Versioning** | The last personal build was 2.3.0 (`versionCode` 9). | 2.3.1 (`versionCode` 10) is the first clean public build. |

### 🧪 Validation — before → after

| Check | 😕 Before | ✅ After |
|---|---|---|
| **JVM tests** | No public clean-build verification. | 36 tests across 6 suites, 0 failures. |
| **Android lint** | No public clean-build verification. | 0 errors. |
| **APK integrity** | Personal build only. | Debug-signed, zipaligned, APK Signature Scheme v2 verified. |

> ⚠️ Historical APKs from **1.2.0 through 2.3.0** are withheld because they embed development-only credentials. Version **2.3.1** is the first APK intended for public use.

---

## 🧩 2.3.0 — CUSTOM SOURCES, REAL YOUTUBE MUSIC, REAL CANVAS

**Released:** 21 October 2026  
**Version:** `2.3.0` (`versionCode` 9)  
**Status:** 🚫 Binary withheld — contained development credentials

### 🔎 Search and catalog — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Custom sources** | The app relied on built-in providers. There was no user-managed JSON source system. | Users can add HTTPS `.json` manifests, with direct endpoints or BitChord-compatible add-ons. |
| **Source validation** | A saved URL was not proven to work. | Ketvoice fetches the manifest and performs a real test search before saving it. |
| **Quality selection** | Quality was not negotiated from the source manifest. | The app reads declared quality options (`LOSSLESS`, `HIGH`, `LOW`, `high`, `normal`, `low`) and negotiates the best available tier. |
| **Downloads from sources** | There was no explicit source download endpoint. | An explicit `download` endpoint is preferred; otherwise the stream is used. `allowDownloads: 0` is respected. |
| **Artist search** | Artist results were only search rows. | Dedicated artist page with photo, description, monthly listeners, albums, and tracks. |
| **Album pages** | Albums were not resolved from real album IDs. | Album pages are built from real YouTube Music `MPRE...` identifiers. |
| **Home feed** | The home screen was generic. | A personalized YouTube Music home feed appears when a valid session is connected. |

### 🔐 YouTube Music session — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Account state** | A mock account system and fake “connected” state existed. | The account area reflects the real YouTube Music web session. No fake account is created. |
| **Login** | There was no real first-party login flow. | A WebView opens Google sign-in, with a manual `SAPISID` cookie fallback. |
| **Session storage** | No real session storage. | The cookie is stored locally with Android Keystore encryption. |
| **Validation** | “Connected” was cosmetic. | The app reports connected only after `account/account_menu` validates the session. |
| **History sync** | No real listening history submission. | A play is counted only when YouTube accepts the real tracking request. No fake history is created. |
| **Search history** | Assumed available. | Search history is not read back; only accepted playback events can be submitted. |
| **Personalized home** | Generic content. | Personalized home is available when the real session supports it. |

### 🎨 Canvas, accent, and settings — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Canvas** | No real Spotify Canvas pipeline. | Real Pathfinder lookup, Genius fallback, local video cache, and optional Wi-Fi-only downloads. |
| **Canvas credentials** | No user-controlled session. | The user provides a Spotify `sp_dc` cookie; no Spotify credential is bundled. |
| **Accent color** | Fixed accent color. | User-selectable accent applied to sliders, timeline, scrubber, controls, progress, downloads, switches, and selected states. |
| **Settings** | Some rows were visual or mock toggles. | Settings are persisted and functional. Choice rows open a sub-menu instead of changing on a single tap. |
| **Animated artwork** | The setting did not fully control Canvas downloads. | Turning off animated artwork also disables Canvas downloads. |
| **Lyrics settings** | Some lyric preferences were not persisted. | Inactive-lyric blur, animated artwork, and Canvas-on-mobile-data persist correctly. |
| **Cache** | Cache behavior was incomplete. | Real cache with a configurable ceiling up to 10 GB and real cache clearing. |

### 🖼️ Player, artwork, and lyrics — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Home logo** | A square background was visible around the logo. | The home logo is transparent and background-free. |
| **Artwork edge** | A visible border or hard edge appeared at the bottom of the artwork. | The artwork fades into the ambient background with no visible border. |
| **Artwork from lyrics** | Tapping the compact artwork could open the action menu instead of returning to the artwork. | Tapping the artwork from lyrics returns directly to the artwork page without opening actions. |
| **Mini-player** | Navigation and compact behavior were less polished. | Improved mini-player, navigation lens, three-dot indicator, and haptics. |

---

## 🎨 2.2.0 — OFFICIAL SEARCH, REAL LYRICS, CLEANER NAVIGATION

**Released:** 20 October 2026  
**Version:** `2.2.0` (`versionCode` 8)  
**Status:** 🚫 Binary withheld — contained development credentials

### 🔎 Search — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Official tracks** | Community uploads could be used as a fallback. | Search requires verified audio tracks with an artist and album. |
| **Old entries** | Unverified cached entries could play. | Entries are re-resolved before YouTube playback. |
| **Slowed/sped-up** | Variants could appear implicitly. | Explicit search is required. |

### ✍️ Lyrics — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Typography** | Smaller, lighter lyrics. | 33 sp Black weight, wider layout. |
| **Highlight timing** | Highlight could ignore real segment duration. | The glow progresses during each source-provided segment. |
| **Syllable timing** | Could imply fabricated syllable timing. | Syllable-by-syllable highlight only when the provider supplies it. |
| **Controls** | Always visible. | Hidden after 2 seconds without contact; artwork tap restores them. |
| **Translations** | Less clearly separated from the original line. | Small subtitle under each original line. |
| **Translation loading** | No progressive priority. | Priorities TTML translations, cached results, then loads near the active line first. |
| **Source labels** | Granularity was unclear. | French labels distinguish syllable, word, line, and unsynchronized text. |

### 🧭 Navigation and artwork — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Tabs** | Search could remain open behind a tab change. | Tabs close search and remain available in compact mode. |
| **Long press** | Navigation long-press behavior was basic. | Liquid Glass lens, drag, haptics, and a three-dot indicator. |
| **Zoom** | Artwork could zoom. | Zoom removed. |
| **Ambient background** | Flat or less adaptive. | Blurred, colored from the artwork, with brightness adaptation. |
| **Demo content** | Demo collections could appear in Explore and Library. | Fake collections removed. Real genres, albums, history, favorites, and downloads are used. |

---

## 🎉 1.0.0 — THE FIRST COMPLETE BUILD

**Released:** 20 October 2026  
**Version:** `1.0.0` (`versionCode` 1)  
**Status:** 🗄️ Legacy archive — unsupported

### Before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Project** | Design specification and reference screenshots only. | First complete Kotlin + Jetpack Compose Android app. |
| **Interface** | No runnable UI. | Liquid Glass foundation, Home, Explore, Library, search, and a custom full-screen player. |
| **Playback** | No playback stack. | Media3 playback, queue, lyrics, downloads, and settings. |
| **Builds** | No APK. | Four archived variants: standard, `compat`, `fix1`, and `fix2`. |
| **Security** | No public distribution decision. | The 1.0.0 builds contain no session cookie or add-on access token. Their help text contains one obsolete example add-on URL. |

> 🗄️ The original 1.0.0 source snapshot was not preserved. The legacy release contains the four APK variants and an archive marker.

---

## 🧱 INTERMEDIATE BUILDS — 1.2.0 → 2.1.0

These development milestones existed, but their detailed before/after history and source snapshots were not preserved:

- `1.2.0`
- `1.3.0`
- `1.3.1`
- `2.0.0`
- `2.1.0`

Their binaries embedded development-only credentials and are **not published**. They are superseded by **2.3.1**.

---

## 📦 RELEASE STATUS AT A GLANCE

| Version | APK published? | Why |
|---|---|---|
| **2.4.0** | ⏳ Pending | Credentials moved to Supabase Vault; sign-off required before publishing the APK. |
| **2.3.1** | ✅ Yes | Clean public build with no embedded credentials. Superseded by 2.4.0. |
| **2.3.0** | 🚫 No | Embedded Spotify cookie and private add-on credentials. |
| **2.2.0** | 🚫 No | Embedded development credentials. |
| **2.1.0** | 🚫 No | Embedded development credentials. |
| **2.0.0** | 🚫 No | Embedded development credentials. |
| **1.3.1** | 🚫 No | Embedded development credentials. |
| **1.3.0** | 🚫 No | Embedded development credentials. |
| **1.2.0** | 🚫 No | Embedded development credentials. |
| **1.0.0** | ✅ Yes | Legacy archive; no session cookie or access token. |

---

<p align="center">
  <strong>Latest published release:</strong>
  <a href="https://github.com/Tysnnn23/Ketvoice/releases/latest">Ketvoice 2.3.1</a><br>
  <strong>Prepared release:</strong> Ketvoice 2.4.0
</p>
