# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 2.6.0 | Yes |
| 2.5.0 | Best effort — previous public release |
| 2.4.0 | Unpublished candidate, superseded by 2.5.0 |
| 2.3.1 | Best effort — previous clean release |
| 2.3.0 and earlier | No |

The server-side isolation introduced in the 2.4.0 candidate ships in 2.5.0: Spotify Canvas and private music-source credentials are handled by a broker instead of the installed app. Version 2.6.0 adds a separate content broker for public publications.

## Reporting a vulnerability

Please use GitHub’s private vulnerability reporting / Security Advisories for this repository. Do not open a public issue containing:

- Spotify `sp_dc` cookies or access tokens
- YouTube `SAPISID` cookies
- Supabase service-role keys, Vault values, or private add-on URLs
- Personal access tokens
- Keystore files or passwords

Include the affected version, Android version, reproduction steps, and the impact. Remove any personal data from logs before attaching them.

## Credentials in this project

No Spotify cookies, add-on tokens, private add-on endpoints, or Supabase service-role keys are committed to this repository or bundled in the 2.6.0 APK.

### Ketvoice API

The APK talks to the `ketvoice-api` Supabase Edge Function. Its sensitive server-side values are stored in Supabase Vault:

- `SP_DC` — Spotify session cookie used for Canvas lookup
- `ADDON_ULTRAMAX` — Ultra MAX source endpoint and token
- `ADDON_LASTBABE` — Last Babe source endpoint and token

The Edge Function reads these values with the Supabase service role. The helper RPC that exposes a named Vault value is restricted to the service role. The function source in `supabase/functions/ketvoice-api/` contains no secret values.

The retired provider's route and Vault entry were deleted in 2.5.0. Requests to unsupported provider tags are rejected.

The app contains the Supabase project URL and the public `anon` JWT. This key is designed to be public; it is not a credential that grants access to Vault or the database. The function uses `verify_jwt`, so requests must carry a valid project JWT.

`verify_jwt` is not a complete abuse barrier. Because the `anon` key can be extracted from any installed client, a determined user can call the function and consume project quota. The function does not expose the underlying cookies or add-on tokens.

### Ketvoice Content and publisher authorization

The APK also talks to the `ketvoice-content` Supabase Edge Function through the same public `anon` JWT. Public reads cover published songs and lyrics only.

Publication writes require a Supabase Auth access token for the authorized mailbox. The function calls Supabase Auth on every write, verifies the user object returned by Auth, requires a confirmed email, and compares it with the configured publisher address. It does not trust a client-supplied email, display name, or metadata field. Unauthenticated and forged tokens are rejected before request bodies are consumed.

The supported publisher mailbox is **konnektsupport@gmail.com**. The address is an authorization identity, not a secret. A matching YouTube profile only reveals the entry point; it grants no write access by itself.

The public media bucket (`ketvoice-public-media`) has no client insert/update/delete policy. The broker validates file signatures, sizes, and extensions and uploads with a server-side service role. Public tables have RLS, published-only read policies, and no `anon` or `authenticated` write grants.

No YouTube session cookie is sent to the content broker. Publisher refresh tokens are encrypted with Android Keystore. The service-role key never enters the APK.

### On-device data

- The YouTube Music web session is stored with Android Keystore encryption.
- The authorized publisher’s Supabase Auth refresh token is stored with Android Keystore encryption.
- Custom JSON source URLs are app settings stored locally.
- The Spotify `sp_dc` cookie is no longer accepted or stored by the app.
- Android backup is disabled (`android:allowBackup="false"`).

Clearing app data or uninstalling the app removes the local session and settings. Logging out of YouTube Music removes the app session but does not revoke the Google account session on the web. Signing out of publishing removes the publisher’s local session.

## Historical binaries

Development builds from 1.2.0 through 2.3.0 contained a Spotify session cookie and private add-on endpoints in their defaults. They are intentionally not published. Do not redistribute copies of those APKs.

Version 2.3.1 is the previous clean binary. Version 2.5.0 includes the server-side isolation from the unpublished 2.4.0 candidate. Version 2.6.0 adds the publication broker and supersedes both; the 2.5.0 release remains published and is not overwritten.

## APK integrity

Release APKs are debug-signed. The signing certificate is a development certificate, not a production identity. Always compare the downloaded APK’s SHA-256 checksum with the value in the release notes.

## Scope

This is a personal, unofficial client. Third-party services can change their APIs or terms at any time. Security reports about YouTube, Spotify, Google, or other providers should be sent to those providers, not to this project.
