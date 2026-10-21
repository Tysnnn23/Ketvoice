# Ketvoice API

`ketvoice-api` is a Supabase Edge Function that brokers music-source search, stream resolution, and Spotify Canvas for the Android app.

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

The `anon` key is public by design. Do not put the `service_role` key, the Spotify cookie, or provider tokens in the APK, the repository, or any public build.

## Operational notes

- Search results are cached in memory for 60 seconds.
- Each provider search has an eight-second timeout.
- An HTTP `429` from a provider skips it for five minutes; the other providers continue.
- Ultra MAX (MP3) and Last Babe (FLAC) are the only configured providers. The retired provider's routing and Vault entry were deleted in 2.5.0.
- Supabase function logs can contain request metadata and IP addresses. Configure retention and access according to your own policy.
