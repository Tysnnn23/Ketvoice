# Imported media edits, optional Sonic Canvas and offline capsules — 2.11.0

## Requested scope

- Replace **audio and/or artwork only on imported publications**. No editor for YouTube, add-on or other catalog songs.
- An explicit **Activer les Canvas vidéo** setting disables video playback and future Canvas video downloads, including explicit song downloads. Existing saved videos are not deleted. This retains the existing `animatedArtwork` preference, so previously disabled artwork stays disabled.
- **Sonic Canvas** is separate, opt-in and off by default.
- Add an **offline capsule** with duration, storage budget and an explicit selection preview.
- **No Ketogether / group-listening session is introduced.** The existing one-device Wi-Fi handoff is unchanged.

## Imported-song editing

Open an imported song's track menu as the verified publisher and choose **Modifier l'audio ou la pochette**. The editor loads the current server record and revision, accepts a replacement audio file (25 MB maximum) and/or image (4 MB maximum), and requires the rights confirmation. Replacing audio does not change the song identity or its lyric records; check lyric timing when replacing a recording.

The content broker still validates the real Supabase Auth user and confirmed `konnektsupport@gmail.com` address. It additionally checks that the imported UUID exists, is published and belongs to that Auth user. A client-supplied identity/email never grants ownership. Other metadata fields, URLs, paths and owner reassignment are rejected.

Uploads use new UUID-versioned object paths instead of Storage upsert. A compare-and-swap PATCH checks both old media paths and the owner so concurrent edits produce a conflict rather than silently overwriting each other. Failed staged uploads are cleaned up only when known not to be committed. Previous media, and possibly committed files after ambiguous network failures, are deliberately retained rather than risking broken links; orphan/old-revision garbage collection is not automated.

The online player checks the latest imported publication before reusing its streaming cache. Cache identity includes the immutable media URL, so a replacement audio file cannot reuse cached bytes from the previous recording. Already downloaded offline copies are not deleted or silently overwritten: the editor explains that they need to be downloaded again. Supabase schema/RLS and public publisher restrictions are unchanged.

`ketvoice-content` version 4 was deployed with JWT verification enabled. Live checks verify health and rejection of unauthenticated edit/read requests. Successful authenticated updates, race conflicts, file validation, ownership and failure handling are covered by mocked tests; no mailbox-owner session or real user song was used for a live edit.

## Sonic Canvas

Media3's PCM tee preserves the output bytes while sending a read-only buffer to a local analyzer. Coarse low/mid/high energy is measured independently per channel, including opposite-phase stereo. Updates are capped by 30 Hz analysis windows. The analyzer does no work while not observed; the tee's pass-through buffer copy remains in the audio chain. No microphone, recording or network telemetry is used.

The player background draws energy-driven gradients using artwork colours. Reduced motion, pause and backgrounding stop reactive analysis, and stale data returns to zero. Unsupported PCM/offload paths remain static, with no fabricated beat animation. This is coarse pre-output energy, not voice detection or sample-accurate beat synchronization. Physical Quest performance and hardware-specific audio paths still need testing.

## Offline capsule

Open Downloads → **Préparer une capsule hors ligne**. Choose a target duration and file budget, optionally include actual home selections, preview the plan and deselect unwanted tracks before preparation. Favorites are prioritized; planning uses known catalog metadata, not invented recommendations.

Preparation is sequential and reuses existing downloads without replacing or evicting files. A per-download byte ledger and streaming checks bound new audio, metadata, lyric sidecars and independent small artwork files. Known oversized Content-Length and oversized chunked bodies are rejected. The limit excludes the separately managed shared playback/image cache. Canvas videos are not fetched for a capsule. Missing providers or larger-than-estimated streams may leave the selection partially ready.

Audio readiness is based on saved files, not progress animation. Available lyric/artwork counts are separate. The latest selection is stored locally and its playable subset is rechecked against real files; playback uses normal downloaded media. Keep Ketvoice open during preparation. Cancellation stops the capsule's own active download and preserves successfully saved audio; an unrelated pre-existing manual download is not cancelled.

## Checks and limitations

- JVM tests cover imported identity/cache revision keys, budget accounting/planning, silent/low/high/float PCM, input integrity and stereo phases.
- Device Compose tests cover edit-menu eligibility, the unauthenticated editor, explicit capsule preview, disabled video Canvas and Media3 tee byte integrity/real energy, in addition to existing UI regressions.
- Node broker checks use fully mocked Auth/database/Storage. Live endpoint checks perform no media upload or database write.
- Ten bundled translation catalogs plus French source cover the new UI; no runtime UI translation service is used.
- Existing Supabase advisory: leaked-password protection is disabled. This predates these changes; no paid settings or auth configuration were changed. [Supabase remediation](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).

## Completed local validation

- **110 JVM tests and 12 Android Compose tests pass per flavor**, with zero failures. Device checks used an Android 15 emulator; the Quest run used a 1280 × 800 dp landscape viewport. These are the same suites run for two flavors, not twice as many distinct tests.
- Both release builds succeed. Lint: **0 errors, 137 warnings, 8 hints** per flavor. An initially stale lint cache was cleared and final validation was rebuilt without the build cache.
- Both signed APKs pass signature verification, ZIP integrity and 16 KB ZIP alignment. The development certificate is unchanged.
- Source/APK credential scan: **0 findings across 1,164 files/archive entries**, including the legacy deny list.
- The signed Quest APK installs and MainActivity starts; the Android notification prompt was reached without a crash in that smoke check. Full signed-release playback was not exercised.
- APKs, hashes and `validation.json` are staged in `release-assets/2.11.0/`, also copied to `C:\Users\Yanis\Downloads\Ketvoice\release-assets\2.11.0\`, and distributed through the [2.11.0 GitHub release](https://github.com/Tysnnn23/Ketvoice/releases/tag/v2.11.0).

No physical Quest, provider-availability guarantee, real owner edit, or full live capsule download is claimed. An end-to-end edit still requires the publisher's mailbox-verified app session.
