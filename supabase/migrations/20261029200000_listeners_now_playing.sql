-- Listening Now : exposition du morceau en cours au site web.
-- La table conserve désormais la pochette du morceau écouté et le RPC de
-- comptage renvoie aussi le titre le plus écouté (titre, artiste, pochette,
-- nombre d'auditeurs) parmi les auditeurs actifs.

alter table public.ketvoice_active_listeners
  add column if not exists track_cover text;

create or replace function public.ketvoice_listener_heartbeat(
  p_client_id text,
  p_title text default null,
  p_artist text default null,
  p_active boolean default true,
  p_cover text default null
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
    insert into public.ketvoice_active_listeners (client_id, track_title, track_artist, track_cover, last_heartbeat)
    values (p_client_id, p_title, p_artist, p_cover, now())
    on conflict (client_id) do update set
      track_title = excluded.track_title,
      track_artist = excluded.track_artist,
      track_cover = excluded.track_cover,
      last_heartbeat = excluded.last_heartbeat;
  end if;
end;
$$;

-- Comptage + morceau en cours (celui qui rassemble le plus d'auditeurs actifs ;
-- départage par la pulsation la plus récente).
create or replace function public.ketvoice_active_listeners_count(p_window_seconds int default 50)
returns json
language plpgsql
security definer
set search_path = 'public'
as $$
declare
  v_count int;
  v_title text;
  v_artist text;
  v_cover text;
  v_track_listeners int;
  v_cutoff timestamptz := now() - make_interval(secs => greatest(p_window_seconds, 15));
begin
  select count(*) into v_count
  from public.ketvoice_active_listeners
  where last_heartbeat >= v_cutoff;

  select track_title, track_artist, track_cover, count(*) into v_title, v_artist, v_cover, v_track_listeners
  from public.ketvoice_active_listeners
  where last_heartbeat >= v_cutoff and track_title is not null
  group by track_title, track_artist, track_cover
  order by count(*) desc, max(last_heartbeat) desc
  limit 1;

  return json_build_object(
    'activeListeners', v_count,
    'windowSeconds', p_window_seconds,
    'updatedAt', extract(epoch from now()) * 1000,
    'nowPlaying', case
      when v_title is null then null
      else json_build_object(
        'title', v_title,
        'artist', v_artist,
        'cover', v_cover,
        'listeners', v_track_listeners
      )
    end
  );
end;
$$;

revoke all on function public.ketvoice_listener_heartbeat(text, text, text, boolean, text) from public, anon, authenticated;
grant execute on function public.ketvoice_listener_heartbeat(text, text, text, boolean, text) to service_role;

revoke all on function public.ketvoice_active_listeners_count(int) from public, anon, authenticated;
grant execute on function public.ketvoice_active_listeners_count(int) to service_role;
