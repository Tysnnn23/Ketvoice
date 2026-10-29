-- Active listeners tracking table and stored procedures.
-- Used by Ketvoice Android app (heartbeat) and Ketvoice Web site (/listeners endpoint).

create table if not exists public.ketvoice_active_listeners (
  client_id text primary key,
  track_title text,
  track_artist text,
  last_heartbeat timestamptz not null default now()
);

create index if not exists ketvoice_active_listeners_hb_idx 
  on public.ketvoice_active_listeners(last_heartbeat);

revoke all on public.ketvoice_active_listeners from anon, authenticated, public;
grant all on public.ketvoice_active_listeners to service_role;

-- Heartbeat function called by ketvoice-api Edge Function on POST /listeners/heartbeat
create or replace function public.ketvoice_listener_heartbeat(
  p_client_id text,
  p_title text default null,
  p_artist text default null,
  p_active boolean default true
)
returns void
language plpgsql
security definer
set search_path = 'public'
as $$
begin
  -- Periodically clean up stale listeners (> 2 minutes)
  delete from public.ketvoice_active_listeners
  where last_heartbeat < now() - interval '2 minutes';

  if p_active is false then
    delete from public.ketvoice_active_listeners where client_id = p_client_id;
  else
    insert into public.ketvoice_active_listeners (client_id, track_title, track_artist, last_heartbeat)
    values (p_client_id, p_title, p_artist, now())
    on conflict (client_id) do update set
      track_title = excluded.track_title,
      track_artist = excluded.track_artist,
      last_heartbeat = excluded.last_heartbeat;
  end if;
end;
$$;

-- Read function returning active listener count and optionally current now-playing snippet
create or replace function public.ketvoice_active_listeners_count(p_window_seconds int default 50)
returns json
language plpgsql
security definer
set search_path = 'public'
as $$
declare
  v_count int;
  v_cutoff timestamptz := now() - make_interval(secs => greatest(p_window_seconds, 15));
begin
  select count(*) into v_count
  from public.ketvoice_active_listeners
  where last_heartbeat >= v_cutoff;

  return json_build_object(
    'activeListeners', v_count,
    'windowSeconds', p_window_seconds,
    'updatedAt', extract(epoch from now()) * 1000
  );
end;
$$;

revoke all on function public.ketvoice_listener_heartbeat(text, text, text, boolean) from public, anon, authenticated;
grant execute on function public.ketvoice_listener_heartbeat(text, text, text, boolean) to service_role;

revoke all on function public.ketvoice_active_listeners_count(int) from public, anon, authenticated;
grant execute on function public.ketvoice_active_listeners_count(int) to service_role;
