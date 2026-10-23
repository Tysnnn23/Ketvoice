# Selected UI polish — 2.10.0

Based on the existing 2.9.0 artist-page and player work; no previous release is replaced.

## Scope

- **D2:** The full player's accent comes from real artwork pixels, with a quantized palette rather than an average of unrelated colours. Accents are lightened to at least 4.5:1 contrast against the reference dark control surface (`#303038`). White lyric text remains white. Missing artwork falls back to the user's existing accent; other screens retain that accent. Artwork and accent changes fade, and the background is darkened for readability. The new setting can disable album accents.
- **A1:** Subtle, bounded artwork depth follows pointer movement or touch without consuming those events. It returns to neutral on exit/release/consumed gestures; backgrounds do not tilt. A dedicated setting and reduced motion both disable it.
- **A2:** Library collection and album cards enter once per screen state, with short staggered fades. Seen keys survive scrolling and saved-state restoration, so a sort does not replay the entrance.
- **A3:** Per-track artwork indicators use `Downloader.busy`/`progress`; active jobs now appear in Downloads before the file is saved. The short checkmark confirmation is shown only after an observed job has a saved audio item. Opening a pre-existing download does not fake a success animation. Optional sidecar finalization is labelled separately from the audio file being saved.
- **A4:** The Albums strip now has Recent / Title A–Z / Artist A–Z sorting. Stable album IDs allow placement animation instead of rebuilding cards. Sorting uses locale-aware collation and does not change playback order.
- **V2:** Track rows and album labels can wrap, while the currently selected player/catalog/queue title can scroll slowly. Other rows do not run marquees. Reduced motion disables marquee movement, and the track menu exposes up to three title lines. The 2.9.0 clickable artist name is retained.
- **V3:** Empty library, collection, download, search and upcoming-queue states explain how to proceed and offer real search/retry/source actions. Remote failures are not presented as a fabricated empty personal collection.
- **V5:** Quest detail text and secondary contrast are strengthened in player, queue, lyrics sources and lists. Existing split panes, landscape manifest, mobile-style icons and normal transport remain. Queue remove targets are 48 dp while icons stay 20 dp. Queue reordering now uses measured visible row centres to handle wrapped titles.
- **A5 is deferred by explicit user choice.** No editable-playlist feature or pretend playlist-add animation is introduced.

## Validation

New JVM checks cover palette sampling, transparent images, deterministic contrast, randomized accent colours, locale-aware sorting, stable track identity and bounded depth coordinates. Device-side Compose tests use in-memory owned-metadata fixtures, not a real account or external media. They cover empty-state actions, album sorting/scrolling, live progress/completion feedback, existing-download behaviour and the Quest queue with a long title/reduced motion.

Completed locally:

- 98 JVM tests pass per flavor; 7 device-side Compose tests pass per flavor on Android 16 (same test suites run for mobile and Quest, not twice as many distinct tests).
- The additional device checks decode a local blue artwork image into an accessible blue accent and confirm that depth does not consume the parent's swipe gesture.
- Quest fixture screens were checked in landscape at 1280 × 800 dp; the long-title queue screenshot shows artwork and all playback/volume/bottom controls on the left, with the queue on the right.
- Both release builds succeed. Lint: 0 errors, 129 warnings, 8 hints per flavor.
- Both APKs are signed with the existing development certificate and pass signature/alignment checks. The source/APK credential scan reports 0 findings across 1,141 files/archive entries, including the legacy deny-list checks.
- APKs, checksums, validation metadata and fixture screenshots are staged in `release-assets/2.10.0/` and distributed through the [2.10.0 release](https://github.com/Tysnnn23/Ketvoice/releases/tag/v2.10.0).

No publisher authorization, backend, Wi-Fi protocol, or playback-source behaviour is intentionally changed. Physical Quest pointer feel and performance still require headset testing. Pixel-colour contrast tests do not constitute a whole-app accessibility audit.
