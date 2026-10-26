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
