# Ketvoice project workflow

- User preference: after completing and validating requested app changes, commit and push the sources to GitHub `main` and publish the corresponding version with both signed mobile and Quest APKs, notes, checksums and validation metadata. Do not wait for another "push" request. If validation, authorization or publication is blocked, report the blocker instead of claiming publication.
- Preserve concurrent user changes and upstream commits. Never force-push or overwrite an existing published release/tag. Keep old releases intact; historical APKs 1.2.0–2.3.0 must not be published.
- SDKs, emulator images, caches, signing keys and credentials do not belong in Git. Stage installable APKs as GitHub release assets, not Git blobs. Verify uploaded downloads against local SHA-256 hashes.
- Quest remains a landscape 2D window with the mobile style: artwork/controls left, lyrics or queue right. Keep the French default, 11 interface languages, Canvas controls, karaoke and reduced-motion support.
- Publishing/editing authorization must be server-enforced for the verified `konnektsupport@gmail.com` account. Media replacement is only for that account's imported publications, never general catalog tracks.
- Ketogether/group listening is deferred until the user explicitly authorizes it. Do not infer authorization from the existing single-device Wi-Fi handoff.
- Distinguish emulator and mocked checks from real owner-session, live download and physical Quest validation. Never claim tests that were not performed.
- Keep `IOS/` aligned with Android fixes where the matching feature exists; document unported counterparts in `IOS/README.md`. Run the Android-to-iOS resource sync/check for catalog and translation changes. Do not treat Python tooling tests as Swift compilation or physical iPhone validation.
