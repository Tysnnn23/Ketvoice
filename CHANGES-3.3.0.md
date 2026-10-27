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
