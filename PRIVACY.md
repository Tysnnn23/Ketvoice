# Privacy Notes

Ketvoice is a local Android client. It does not operate a Ketvoice backend and does not include analytics, advertising, or crash-reporting SDKs.

## Data stored on the device

- App settings, theme, accent color, cache limits, and source URLs.
- Playback history, favorites, queue state, and downloaded files.
- Cached audio, artwork, lyrics, and Canvas videos.
- A YouTube Music web session when the user signs in.
- A Spotify Canvas cookie when the user configures Canvas.

The YouTube session is encrypted with Android Keystore. Other settings are stored in the app’s private preference storage. Android backup is disabled.

## Network requests

The app makes direct requests to the providers needed for the feature being used, which may include:

- YouTube Music / Google for catalog data, streams, account validation, and optional history tracking
- Deezer, iTunes, and YouTube image CDNs for artwork
- LRCLIB, NetEase, Genius, BetterLyrics, LiriQo, and other lyric providers
- Google Translate or MyMemory for optional translations
- Spotify for Canvas lookup and playback
- Any custom JSON source added by the user

Those providers receive the request data required for the operation, such as the search query, track metadata, or a session cookie. Their own privacy policies apply.

A user-provided JSON source receives search queries and track identifiers and may log the user’s IP address. Only add sources you trust.

## Removing data

Use **Settings → Storage** to clear caches, log out of YouTube Music to remove the app session, or clear Android app data / uninstall the app to remove all local state.

## Contact

For privacy questions or security reports, use the repository’s GitHub Security Advisories rather than a public issue containing personal data.
