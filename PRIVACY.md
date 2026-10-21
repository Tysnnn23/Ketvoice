# Privacy Notes

Ketvoice is an Android client with a small Supabase-hosted backend, **Ketvoice API**, used for music-source search, stream resolution, and Spotify Canvas. It does not include analytics, advertising, or crash-reporting SDKs, and it does not create a Ketvoice account.

## Data stored on the device

- App settings, interface language, theme, accent color, cache limits, and custom source URLs.
- Playback history, favorites, queue state, and downloaded files.
- Cached audio, artwork, lyrics, and Canvas videos.
- Downloaded lyrics in JSON/LRC sidecars and references to persistent Canvas files. Album reuse keys include the artist to avoid mixing unrelated albums with the same title.
- A YouTube Music web session when the user signs in.

The YouTube session is encrypted with Android Keystore. Other settings are stored in the app’s private preference storage. Android backup is disabled. The Spotify `sp_dc` cookie is no longer accepted or stored by the app.

## Network requests

### Ketvoice API

Search, stream, and Canvas requests go to the `ketvoice-api` Supabase Edge Function. Those requests contain the search query or track metadata such as title, artist, and Spotify ID. The function returns track metadata, short-lived stream URLs, and Canvas URLs.

Spotify and source credentials remain in Supabase Vault and are not returned to the app. Supabase may retain function invocation logs containing request metadata, IP address, and timestamps according to the project’s configuration.

### Direct providers

Interface translations ship inside the APK and do not make network requests. Optional **lyric translation** is a separate feature and may contact translation providers. Downloading a song also checks lyric providers and requests its Canvas if available, even when animated artwork is not displayed.

The app also makes direct requests to the providers needed for the feature being used, which may include:

- YouTube Music / Google for catalog data, streams, account validation, and optional history tracking
- Deezer, iTunes, and YouTube image CDNs for artwork
- LRCLIB, NetEase, Genius, BetterLyrics, LiriQo, and other lyric providers
- Google Translate or MyMemory for optional translations
- Spotify CDNs for Canvas media
- Any custom JSON source added by the user

Those providers receive the request data required for the operation, such as the search query, track metadata, or a session cookie. Their own privacy policies apply.

A custom JSON source receives search queries and track identifiers and may log the user’s IP address. Only add sources you trust. Ketvoice does not send the YouTube session or Spotify credentials to custom sources.

## Removing data

Use **Settings → Storage** to clear the audio playback cache. Downloaded song lyrics and shared Canvas files are retained separately. Deleting a download removes its audio and lyric sidecars; a shared Canvas is retained for reuse. Log out of YouTube Music to remove the app session, or clear Android app data / uninstall the app to remove all local state. Server-side function logs are retained according to the Supabase project configuration.

## Contact

For privacy questions or security reports, use the repository’s GitHub Security Advisories rather than a public issue containing personal data.
