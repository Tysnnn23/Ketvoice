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
