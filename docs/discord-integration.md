# Discord linking and live presence

Implemented in 3.13.0 with the official **Discord Social SDK 1.10.19337** for
Android. Ketvoice never asks for a Discord password, never uses a user token or
self-bot, and never fabricates a linked state.

## Application setup

- Application ID: `1566454532890820668` (public, not a secret).
- OAuth2 redirect **must be registered** in the Developer Portal:
  `https://ketvoice.netlify.app/discord.html` (HTTPS, forwarded to `ketvoice://discord`). On 2026-11-01 the portal had
  no redirect configured at all, so the account-link page could never return to
  Ketvoice. The HTTPS page removes the custom-scheme restriction; the in-app
  WebView also completes directly when the bridge URL carries `code`/`error`.- Scopes: `Client::GetDefaultPresenceScopes()` (`openid sdk.social_layer_presence`).
- The Android manifest merges the SDK's `com.discord.socialsdk.AuthenticationActivity`
  and adds the `discord-1566454532890820668` scheme so the browser/Discord app can
  return the authorization code to Ketvoice.

## Flow

The Discord panel lives in **Settings → Compte**. Presence through the installed
Discord app is the default path: the button opens the Discord app, waits for it to
start, then calls `Client::Connect` without a token, which uses the local Discord
session. No OAuth page is opened. A missing app is reported explicitly, and a
connect attempt that does not reach `Status::Ready` within 12 seconds stops with
an explicit error instead of spinning; the last SDK log line is shown under it.

Account linking remains an optional action:

1. The user taps **Link my Discord account**.
2. Ketvoice generates a PKCE verifier, an S256 challenge and a random `state`, then
   opens `https://discord.com/oauth2/authorize` inside its own WebView
   (`DiscordAuthActivity`).
3. Discord redirects to `discord-1566454532890820668:/authorize/callback`. The
   WebView intercepts that URL before Android has to resolve it, so the browser
   and Discord app hand-off that silently dropped the code are no longer involved.
   A `ketvoice://discord?...` bridge and the exported callback activity remain as
   fallbacks.
4. Ketvoice checks the scheme, path and `state`, then passes the code, verifier and
   redirect URI to the SDK (`Client::GetToken` → `Client::UpdateToken`).
5. The access token lasts seven days; the non-expiring refresh token is stored
   encrypted with an Android Keystore AES-GCM key (`ketvoice.discord.refresh`).
   On startup `Client::RefreshToken` renews the access token.
6. `Client::Connect` reaches `Status::Ready`; `GetCurrentUserV2` fills the account
   name shown in Ketvoice. Returning to Ketvoice without a code cancels the
   pending link after 8 seconds, and an absolute three-minute timeout covers the
   rest. The Discord app is tried first (it reuses the signed-in session and
   delivers the redirect as an intent); the browser is the fallback.

## Presence

Only while the user has enabled **Show what I'm listening to** *and* a track is
actually playing:

- `type = Playing`, `details` = track title, `state` = artist (both clamped to
  Discord's 128-character limit).
- Large image = HTTPS cover URL when it is at most 300 characters.
- `timestamps.start` = now − playback position, so Discord counts up correctly.
- Buttons: **Download ketvoice** → `https://ketvoice.netlify.app/` and
  **Listen now** → the public `listen.html` track link. Never an expiring media
  stream URL or a local file path.
- Presence is cleared on pause/stop, unlink and opt-out.

## OAuth2 token fallback (advanced)

Settings → Compte also accepts a pasted OAuth2 bearer token via
`Client::UpdateToken(AuthorizationTokenType::Bearer, …)`, followed by `Connect`.
Only a token issued for this Discord application works; regular Discord user
tokens are rejected by the SDK. The token is stored encrypted with the same
Keystore key, and a token the SDK refuses is deleted instead of being restored on
every launch. The SDK log line is surfaced in the panel to explain a refusal.

## Source distribution
The SDK AAR is **not** committed. `app/libs/discord/discord_partner_sdk.aar` is
ignored by Git and must be copied from the owner's SDK download before building
with Discord support. Without the AAR the project still builds; the Discord UI is
hidden and no presence code is compiled (bridge: `app/src/discord/`).

## Validation status

- JNI bridge compiles and links against the AAR's prefab module (NDK 27, arm64;
  Quest also builds x86_64).
- Payload rules (limits, buttons, HTTPS-only assets, elapsed timer, paused → no
  presence) are covered by host unit tests.
- **Not validated on hardware**: this environment has no phone, Quest or
  accelerated emulator, so the real OAuth redirect, Discord-side presence and the
  two buttons were not observed. Do not describe them as device-tested until an
  owner-session check is performed with a second Discord account (Discord does not
  show your own presence buttons to you).
