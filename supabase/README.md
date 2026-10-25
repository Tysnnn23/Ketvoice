# Ketvoice API and Ketvoice Content

The `supabase/functions` directory contains two Edge Functions:

- `ketvoice-api` brokers music-source search, stream resolution, and Spotify Canvas for the Android app.
- `ketvoice-content` serves published songs/lyrics and handles restricted publication writes.

Open `admin/ketvoice-admin.html` locally for the owner-only desktop editor.
The browser uses the public Supabase API key and a short-lived owner session;
it never contains the service role key. `/admin/sources` in `ketvoice-content`
manages the server-side `ketvoice_managed_sources` table. `ketvoice-api` reads
its active rows for every search and stream resolution, then validates and
fetches their manifests. Expiring manifest URLs must be replaced by the owner.

## Ketvoice API

It keeps the sensitive provider values out of the APK:

- `SP_DC` — Spotify Canvas session cookie
- `ADDON_ULTRAMAX` — Ultra MAX source base URL/token
- `ADDON_LASTBABE` — Last Babe source base URL/token

The function source contains no secret values. It reads a named Vault entry through a service-role-only RPC and caches the result in memory for five minutes.

## Routes

| Route | Purpose |
| --- | --- |
| `GET /health` | Confirms the function is running and reports which server secrets are configured |
| `GET /source.json` | Describes the built-in source card to the app |
| `GET /search?q=...` | Searches the configured providers and returns normalized tracks |
| `GET /stream?source=um\|last&id=...&title=...&artist=...` | Resolves a stream URL; unsupported provider tags are rejected |
| `GET /canvas?spotifyId=...&title=...&artist=...` | Resolves a Spotify Canvas URL |

All routes return JSON. The function is deployed with `verify_jwt` enabled.

## Ketvoice Content

The content broker exposes published content to every client and accepts writes only from the verified publisher mailbox.

| Route | Auth | Purpose |
| --- | --- | --- |
| `GET /health` | project JWT | Reports that the content function is running |
| `GET /search?q=...` | project JWT | Searches published songs via `ketvoice_search_catalog` |
| `GET /track?id=<uuid>` | project JWT | Returns one published song and its public stream URL |
| `GET /lyrics?trackId=...&title=...&artist=...&isrc=...&durationMs=...` | project JWT | Returns published lyric versions, newest first |
| `GET /admin/session` | `x-ketvoice-admin: <Supabase access token>` | Verifies the publisher session |
| `POST /admin/song` | admin token | Multipart publication of audio, optional cover, and optional lyrics |
| `POST /admin/lyrics` | admin token | Publishes one TTML/LRC lyrics version |

The admin token is verified with Supabase Auth on every write (`/auth/v1/user`). The function requires a confirmed email matching `ADMIN_EMAIL` in `index.ts` (default: `konnektsupport@gmail.com`). It never trusts a client-supplied email or user metadata.

### Schema and storage

Apply `supabase/migrations/20261021225132_public_content.sql` before deploying the function. It creates:

- `ketvoice_tracks` and `ketvoice_lyrics` with constraints and indexes.
- Published-only RLS read policies; `anon`/`authenticated` receive no write grants.
- `ketvoice_search_catalog(text)`, executable by clients for published search.
- The public `ketvoice-public-media` bucket with a 25 MiB limit and an audio/image MIME allow-list. There are intentionally no client write policies on `storage.objects`.

The function reads `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` from the Edge Function environment. Never expose the service-role key to the app or commit it. If you self-host, change `ADMIN_EMAIL` to your own publisher address and update the same address in `PublisherAccess.kt`.

## Self-hosting

1. Create the Vault secrets listed above in your Supabase project.
2. Create the service-role-only helper:

```sql
create or replace function public.ketvoice_get_secret(p_name text)
returns text
language sql
security definer
set search_path to 'vault', 'public'
as $$
  select decrypted_secret
  from vault.decrypted_secrets
  where name = p_name
  limit 1;
$$;

revoke all on function public.ketvoice_get_secret(text) from public, anon, authenticated;
grant execute on function public.ketvoice_get_secret(text) to service_role;
```

3. Deploy `functions/ketvoice-api/index.ts` as an Edge Function with JWT verification enabled.
4. Set `BASE` and `ANON_KEY` in `KetvoiceApi.kt` to your project URL and public `anon` key.
5. Apply `migrations/20261021225132_public_content.sql` and deploy `functions/ketvoice-content` with JWT verification enabled.
6. Set `ROOT` and `ANON_KEY` in `PublicationApi.kt` to the same project. Change `ADMIN_EMAIL` in the content function and `PublisherAccess.kt` if you use a different mailbox.
7. Configure Supabase Auth email delivery for the publisher. The app accepts either a `token`/`token_hash` magic link or an email OTP; copy the link into the app without opening it if the template does not show a code.

The `anon` key is public by design. Do not put the `service_role` key, the Spotify cookie, or provider tokens in the APK, the repository, or any public build.

## Operational notes

- Search results are cached in memory for 60 seconds.
- Each provider search has an eight-second timeout.
- An HTTP `429` from a provider skips it for five minutes; the other providers continue.
- Ultra MAX (MP3) and Last Babe (FLAC) are the only configured providers. The retired provider's routing and Vault entry were deleted in 2.5.0.
- Publication uploads are validated server-side (audio/image signatures, sizes, TTML DTD/entity rejection, LRC timing). Failed uploads are cleaned up.
- Supabase function logs can contain request metadata and IP addresses. Configure retention and access according to your own policy.
