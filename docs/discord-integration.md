# Discord linking and live presence

Implemented in 3.13.0 with the official **Discord Social SDK 1.10.19337** for
Android. Ketvoice never asks for a Discord password, never uses a user token or
self-bot, and never fabricates a linked state.

## Application setup

- Application ID: `1566454532890820668` (public, not a secret).
- OAuth2 redirect registered in the Developer Portal:
  `discord-1566454532890820668:/authorize/callback`
- Scopes: `Client::GetDefaultPresenceScopes()` (`openid sdk.social_layer_presence`).
- The Android manifest merges the SDK's `com.discord.socialsdk.AuthenticationActivity`
  and adds the `discord-1566454532890820668` scheme so the browser/Discord app can
  return the authorization code to Ketvoice.

## Flow

1. The user opens the account panel and taps **Link my Discord account**.
2. Ketvoice generates a PKCE verifier, an S256 challenge and a random `state`, then
   opens `https://discord.com/oauth2/authorize` in a browser tab.
3. Discord redirects to `discord-1566454532890820668:/authorize/callback`, which is
   handled by Ketvoice's own exported `DiscordCallbackActivity` (the SDK helper is
   not relied on: it could finish without invoking the native callback when the
   Discord app returned the user directly to Ketvoice).
4. Ketvoice checks the scheme, path and `state`, then passes the code, verifier and
   redirect URI to the SDK (`Client::GetToken` → `Client::UpdateToken`).
5. The access token lasts seven days; the non-expiring refresh token is stored
   encrypted with an Android Keystore AES-GCM key (`ketvoice.discord.refresh`).
   On startup `Client::RefreshToken` renews the access token.
6. `Client::Connect` reaches `Status::Ready`; `GetCurrentUserV2` fills the account
   name shown in Ketvoice. A three-minute timeout clears a stalled attempt.

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
