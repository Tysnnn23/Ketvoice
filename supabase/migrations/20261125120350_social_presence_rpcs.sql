-- Windows 2.0 social, part 6 of 9: writing and reading « Activité des amis » (see part 5 for the rules). Re-runnable.

-- What the app calls every minute or so while sharing (an upsert needs no read right on the owner column this
-- way). Statuses: ok, off (« Partager mon écoute » is off: nothing written, any old row removed).
create or replace function public.ketvoice_presence_put(p_track jsonb, p_playing boolean, p_room text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if not public.ketvoice_shares_listening(uid) then
    delete from public.ketvoice_presence pr where pr.user_id = uid;
    return jsonb_build_object('status', 'off');
  end if;
  insert into public.ketvoice_presence (user_id, track, playing, room) values (uid, p_track, coalesce(p_playing, false), p_room)
  on conflict (user_id) do update set track = excluded.track, playing = excluded.playing, room = excluded.room;
  return jsonb_build_object('status', 'ok');
end $$;
revoke all on function public.ketvoice_presence_put(jsonb, boolean, text) from public, anon;
grant execute on function public.ketvoice_presence_put(jsonb, boolean, text) to authenticated;

-- What the caller's friends listen to now: friends who share, fresh rows, newest first.
-- Each line: {handle, display_name, accent, has_avatar, track | null, playing, room | null, at}.
create or replace function public.ketvoice_friends_now() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  return coalesce((select jsonb_agg(jsonb_build_object('handle', p.handle, 'display_name', p.display_name,
      'accent', p.accent, 'has_avatar', p.avatar_url is not null, 'track', pr.track, 'playing', pr.playing,
      'room', pr.room, 'at', pr.updated_at) order by pr.updated_at desc)
    from public.ketvoice_friendships f
    join public.ketvoice_profiles p on p.id = case when f.requester = uid then f.addressee else f.requester end
    join public.ketvoice_presence pr on pr.user_id = p.id
    where f.status = 'accepted' and (f.requester = uid or f.addressee = uid)
      and p.share_listening and p.handle is not null and pr.updated_at > now() - interval '10 minutes'
      and not public.ketvoice_blocked_between(uid, p.id)), '[]'::jsonb);
end $$;
revoke all on function public.ketvoice_friends_now() from public, anon;
grant execute on function public.ketvoice_friends_now() to authenticated;
