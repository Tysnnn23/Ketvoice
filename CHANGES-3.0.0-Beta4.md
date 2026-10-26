# Ketvoice 3.0.0-Beta4

**versionCode 26** — upgrades in place over 3.0.0-Beta3-rebuild.1 (same signing certificate).

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
