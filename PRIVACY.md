# Privacy Notes

Ketvoice is an Android client with two small Supabase-hosted backends, **Ketvoice API** (music-source search, stream resolution, and Spotify Canvas) and **Ketvoice Content** (public publication search and restricted publishing). It does not include analytics, advertising, or crash-reporting SDKs, and listeners do not create a Ketvoice account. Only the authorized publisher signs in with Supabase Auth to publish public songs or lyrics.

## Data stored on the device

- App settings, interface language, theme, accent color, cache limits, and custom source URLs.
- Playback history, favorites, queue state, and downloaded files.
- Cached audio, artwork, lyrics, and Canvas videos.
- Downloaded lyrics in JSON/LRC sidecars and references to persistent Canvas files. Album reuse keys include the artist to avoid mixing unrelated albums with the same title.
- A YouTube Music web session when the user signs in.
- A Supabase Auth session for the authorized publisher, including an access token and a refresh token encrypted with Android Keystore.

The YouTube session is encrypted with Android Keystore. Other settings are stored in the app’s private preference storage. Android backup is disabled. The Spotify `sp_dc` cookie is no longer accepted or stored by the app.

## Network requests

### Ketvoice API

Search, stream, and Canvas requests go to the `ketvoice-api` Supabase Edge Function. Those requests contain the search query or track metadata such as title, artist, and Spotify ID. The function returns track metadata, short-lived stream URLs, and Canvas URLs.

Spotify and source credentials remain in Supabase Vault and are not returned to the app. Supabase may retain function invocation logs containing request metadata, IP address, and timestamps according to the project’s configuration.

### Ketvoice Content and publishing

Public song/lyrics searches and streaming metadata go to the `ketvoice-content` function. They contain the search query, track ID/title/artist, and requested lyrics identity.

The authorized publisher signs in through Supabase Auth using a one-time email link or code. The app stores the resulting session; the refresh token is encrypted with Android Keystore. The function checks the Auth user’s confirmed email on every write and never trusts an email supplied by the client.

From 2.6.1, the email link can return to the app through `ketvoice://auth`. Callback credentials are exchanged with Supabase for a validated session and checked by the publication broker; they are not copied into the on-screen entry field or retained in the Activity launch intent. The custom scheme is not a domain-verified Android App Link; choose only Ketvoice when Android offers an app choice.

Audio files, optional cover art, lyrics, title, artist, album, and the rights confirmation are uploaded to the project’s public storage bucket and public tables. Published content is visible to every Ketvoice user and to anyone with its public media URL. The publisher’s email address is used for authorization but is not included in public track metadata. The YouTube session cookie is not sent to the publication service.

Published content can be removed by the project owner through Supabase. Deleting local app data does not remove already published content.

### Optional local Wi-Fi handoff (2.8.0)

Disabled by default. While enabled and the app is in the foreground, Ketvoice uses mDNS to advertise a temporary instance identifier, device label, protocol version and LAN port. Nearby devices on the same private IPv4 subnet can discover that announcement. Discovery is stopped when the app goes into the background or the option is disabled.

After an explicit request, the recipient sees a confirmation dialog. Accepting permits a single playback handoff containing catalog track identifiers, title/artist/album, duration, position, playback flags and up to 50 queue entries. No account cookies/tokens, source configuration, local files or stream URLs are included. The recipient resolves online catalog media through its normal providers, which can require Internet access.

Local messages use unencrypted HTTP. There is no cryptographic pairing or verified device identity: names are self-reported, and another network participant may observe metadata and position. Use this feature only on trusted private networks. Receiver approval protects against unattended requests, not network interception. Handoff protocol state is temporary and is not uploaded to a Ketvoice cloud service.

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

Use **Settings → Storage** to clear the audio playback cache. Downloaded song lyrics and shared Canvas files are retained separately. Deleting a download removes its audio and lyric sidecars; a shared Canvas is retained for reuse. Log out of YouTube Music to remove the app session, sign out of publishing to remove the publisher refresh token, or clear Android app data / uninstall the app to remove all local state. Published public content is removed separately on the server. Server-side function logs are retained according to the Supabase project configuration.

## Contact

For privacy questions or security reports, use the repository’s GitHub Security Advisories rather than a public issue containing personal data.
