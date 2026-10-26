-- Rolling-window rate limit for the public ketvoice-api broker.
-- The Edge Function (service role) calls ketvoice_api_take before serving
-- /search, /stream and /canvas. Anonymous clients get no direct access.

create table public.ketvoice_api_hits (
  ip text not null,
  route text not null,
  at timestamptz not null default now()
);

create index ketvoice_api_hits_window_idx on public.ketvoice_api_hits(ip, route, at);
create index ketvoice_api_hits_cleanup_idx on public.ketvoice_api_hits(at);

revoke all on public.ketvoice_api_hits from anon, authenticated, public;
grant all on public.ketvoice_api_hits to service_role;

create function public.ketvoice_api_take(p_ip text, p_route text, p_limit int, p_window_s int)
returns boolean
language plpgsql
security definer
set search_path = 'public'
as $$
declare
  v_count int;
  v_cutoff timestamptz := now() - make_interval(secs => greatest(p_window_s, 1));
begin
  delete from public.ketvoice_api_hits where at < now() - interval '1 hour';
  select count(*) into v_count
  from public.ketvoice_api_hits
  where ip = p_ip and route = p_route and at > v_cutoff;
  if v_count >= greatest(p_limit, 1) then
    return false;
  end if;
  insert into public.ketvoice_api_hits(ip, route) values (p_ip, p_route);
  return true;
end;
$$;

revoke all on function public.ketvoice_api_take(text, text, int, int) from public, anon, authenticated;
grant execute on function public.ketvoice_api_take(text, text, int, int) to service_role;
