# Discord presence integration status

Official documentation checked on 2026-11-01:

- [Android support](https://docs.discord.com/developers/discord-social-sdk/core-concepts/mobile.md)
- [Mobile account linking](https://docs.discord.com/developers/discord-social-sdk/development-guides/account-linking-on-mobile.md)
- [Presence and buttons](https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence.md)

Discord Social SDK supports Android, linked accounts, listening activities and
two URL buttons. Android RPC presence without linking requires SDK 1.10+ and a
signed-in Discord app. It is not a generic REST endpoint that an OAuth `identify`
token or a bot can use to change a user's presence. User tokens/self-bots must
never be requested or used.

## Blocking prerequisites

The owner supplied Application ID `1566454532890820668`; Discord's public RPC
metadata confirms its name is **Ketvoice**. This public ID is not a secret.
The redirect to register in OAuth2 is:

`discord-1566454532890820668:/authorize/callback`

The Social SDK download page redirects to login in the available browser session.
SDK activation, accepting its terms, the Android AAR/header download and callback
registration still require the owner's developer-portal session. Public RPC
metadata does not establish whether that private SDK configuration is complete.
Do not fabricate a linked status or publish a non-functional connection button.
PKCE and OAuth state validation remain mandatory; never embed a client secret in
the APK. For a confidential client, token exchange stays on the server.

## Intended behavior after provisioning

- Explicit opt-in from Account; clear unlink and presence privacy controls.
- SDK-managed authentication, protected token storage and cancellation on logout.
- Publish track/artist/cover and position while actually playing; clear on stop,
  logout, opt-out and service shutdown. Reconcile seeks and pauses.
- `Download ketvoice` → `https://ketvoice.netlify.app/`.
- `Listen now` → the current track's public `ShareLinks` URL, never its expiring
  media stream URL or a private/local file URL.
- Confirm both buttons using another Discord account; Discord does not show a
  user's own presence buttons to that same user.
- Verify Android and Quest compatibility separately. No such live session test
  has been performed and the integration is not shipped in 3.12.1.
