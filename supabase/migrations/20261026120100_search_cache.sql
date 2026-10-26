-- Server-side search cache for the ketvoice-api broker. The Edge Function
-- isolates are ephemeral, so the previous in-memory 60 s cache rarely helped.
-- Only the service role (the function) touches this table.

create table public.ketvoice_search_cache (
  query text primary key check (length(query) between 2 and 160),
  results jsonb not null,
  fetched_at timestamptz not null default now()
);

revoke all on public.ketvoice_search_cache from anon, authenticated, public;
grant all on public.ketvoice_search_cache to service_role;

create function public.ketvoice_search_cache_get(p_query text, p_max_age_s int)
returns jsonb
language sql
security definer
set search_path = 'public'
as $$
  select results from public.ketvoice_search_cache
  where query = p_query and fetched_at > now() - make_interval(secs => greatest(p_max_age_s, 1))
  limit 1;
$$;

create function public.ketvoice_search_cache_put(p_query text, p_results jsonb)
returns void
language sql
security definer
set search_path = 'public'
as $$
  delete from public.ketvoice_search_cache where fetched_at < now() - interval '7 days';
  insert into public.ketvoice_search_cache(query, results, fetched_at)
  values (p_query, p_results, now())
  on conflict (query) do update
    set results = excluded.results, fetched_at = excluded.fetched_at;
$$;

revoke all on function public.ketvoice_search_cache_get(text, int) from public, anon, authenticated;
revoke all on function public.ketvoice_search_cache_put(text, jsonb) from public, anon, authenticated;
grant execute on function public.ketvoice_search_cache_get(text, int) to service_role;
grant execute on function public.ketvoice_search_cache_put(text, jsonb) to service_role;
