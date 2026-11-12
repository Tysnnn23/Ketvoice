# 4.7 improvements — test build

This branch builds on 4.7.0 and is not a published release. It keeps the existing
application ID and version so it can be tested as an update only when the signing
certificate matches the installed app.

The GitHub-hosted test APKs do not contain the owner-supplied Discord SDK and do
not offer Discord integration, as explicitly selected for this test build. Locally,
`-PketvoiceWithoutDiscord=true` exercises the same SDK-free configuration without
moving or deleting the owner's SDK.

## What to test

- Source order is respected when progressive quality is disabled. Progressive
  mode starts an available source and can upgrade later. Search results appear as
  each provider answers; confidently matched recordings offer source buttons.
- Reopening the app restores the queue, duplicate occurrences, playback position
  and playback modes without starting audio until the user presses Play.
- Audio statistics show the selected decoder format and known source. Per-track
  lyric timing can be adjusted or reset from the lyrics source sheet.
- The Storage settings offer personal backup export and a preview before import.
  Existing playlists are never replaced; favorites and history are merged.
  Accounts, credentials, sensitive settings and media files are excluded. Local
  media permissions may need to be granted again on another device. If an import
  is interrupted, already merged additions remain and the UI reports an error.
- Home uses locally available favorites and listening history, and avoids recent
  repeats where alternatives exist. Existing personalized YouTube Home remains.
- Offline DASH uses a dedicated non-evicting cache, not the streaming cache. It
  requires an explicit `allowDownloads` grant, the stable Ketvoice Lossless proxy,
  a finite single-period/single-representation unprotected FLAC manifest and HTTPS
  segments. Other DASH layouts fall back rather than bypassing protection. The
  current Lossless manifest does not declare that grant, so this path is not enabled
  for that service until its operator explicitly declares permission and the source
  manifest is refreshed. A partial download with an expired segment URL may need
  to be cancelled and retried; cancellation removes only that track's partial data.

## Validation boundaries

Robolectric and fixture tests do not prove audible playback, actual decoding,
performance, battery behavior, Android system picker behavior, or Quest hardware
behavior. The offline DASH tests use synthetic bytes, not real audio. No physical
phone or Quest was connected at the time of this test build. Never uninstall an
existing installation to resolve a signing conflict without backing up its data.
