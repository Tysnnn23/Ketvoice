<h1 align="center">🎧 KETVOICE — RELEASE HISTORY</h1>

<p align="center">
  <strong>Every public version, explained as a before → after story.</strong><br>
  Eleven interface languages, French by default. These release notes are in English.
</p>

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
