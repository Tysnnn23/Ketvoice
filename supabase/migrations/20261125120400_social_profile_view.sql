-- Windows 2.0 social, part 7 of 9: reading a profile in the app. Re-runnable.
-- Who sees what (the app's ProfileRules.canSee is the same rule, tested on both sides):
--   * the owner (relation `me`) sees everything;
--   * a friend (an accepted friendship, no block) sees the identity and every module set to friends or public;
--   * anyone else (`stranger`) sees the identity and the public modules only while the profile is public
--     (ketvoice_profiles.is_public); a private profile shows them its handle and the friendship buttons, nothing else.
-- A block either way answers null, like an unknown handle.

create or replace function public.ketvoice_profile_can_see(p_relation text, p_reach text, p_public boolean) returns boolean
language sql immutable set search_path = '' as $$
  select case p_relation
    when 'me' then true
    when 'friend' then coalesce(p_reach, '') in ('friends', 'public')
    else coalesce(p_reach, '') = 'public' and coalesce(p_public, false) end
$$;
grant execute on function public.ketvoice_profile_can_see(text, text, boolean) to anon, authenticated;

-- The title in a now_playing document, when it was written less than 30 minutes ago (the 6.3 profile's rule).
create or replace function public.ketvoice_listening_of(p_user uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  doc jsonb;
  seen timestamptz;
  entry jsonb;
begin
  select l.doc, l.updated_at into doc, seen from public.ketvoice_library l where l.user_id = p_user and l.kind = 'now_playing';
  if not found or seen <= now() - interval '30 minutes' or coalesce(jsonb_typeof(doc -> 'queue'), '') <> 'array'
     or coalesce(doc ->> 'index', '') !~ '^[0-9]{1,3}$' then
    return null;
  end if;
  entry := (doc -> 'queue') -> ((doc ->> 'index')::integer);
  if coalesce(jsonb_typeof(entry), '') <> 'object' or coalesce(jsonb_typeof(entry -> 'title'), '') <> 'string'
     or coalesce(entry ->> 'id', '') like 'local:%' then
    return null;
  end if;
  return public.ketvoice_activity_track(entry) || jsonb_build_object('playing', coalesce(doc ->> 'playing', '') = 'true');
end $$;
revoke all on function public.ketvoice_listening_of(uuid) from public, anon, authenticated;

-- The modules a relation may see, as {top: {reach, data}, pinned: {…}}; `now` is answered as `listening`.
create or replace function public.ketvoice_profile_modules_for(p_user uuid, p_relation text, p_public boolean) returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_object_agg(m.module, jsonb_build_object('reach', m.reach, 'data',
      case when m.module = 'now' then '{}'::jsonb else m.data end)), '{}'::jsonb)
  from public.ketvoice_profile_modules m
  where m.user_id = p_user and public.ketvoice_profile_can_see(p_relation, m.reach, p_public)
$$;
revoke all on function public.ketvoice_profile_modules_for(uuid, text, boolean) from public, anon, authenticated;

-- A profile as the signed-in caller may see it, or null. {handle, relation, friend: self|none|outgoing|incoming|
-- friends, ref, private, display_name, bio, accent, has_avatar, has_banner, is_public, modules, listening}.
create or replace function public.ketvoice_profile_view(handle text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  wanted text := public.ketvoice_clean_handle(handle);
  prof public.ketvoice_profiles%rowtype;
  pair public.ketvoice_friendships%rowtype;
  rel text;
  friend text;
  shown jsonb;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  select p.* into prof from public.ketvoice_profiles p where p.handle = wanted;
  if not found or public.ketvoice_blocked_between(uid, prof.id) then
    return null;
  end if;
  select f.* into pair from public.ketvoice_friendships f
  where least(f.requester, f.addressee) = least(uid, prof.id) and greatest(f.requester, f.addressee) = greatest(uid, prof.id);
  rel := case when prof.id = uid then 'me' when pair.status = 'accepted' then 'friend' else 'stranger' end;
  friend := case when prof.id = uid then 'self' when pair.id is null then 'none' when pair.status = 'accepted' then 'friends'
    when pair.requester = uid then 'outgoing' else 'incoming' end;
  if rel = 'stranger' and not prof.is_public then
    return jsonb_build_object('handle', prof.handle, 'relation', rel, 'friend', friend, 'ref', pair.id, 'private', true);
  end if;
  if exists (select 1 from public.ketvoice_profile_modules m where m.user_id = prof.id and m.module = 'now'
             and public.ketvoice_profile_can_see(rel, m.reach, prof.is_public)) then
    shown := public.ketvoice_listening_of(prof.id);
  end if;
  return jsonb_build_object('handle', prof.handle, 'relation', rel, 'friend', friend, 'ref', pair.id, 'private', false,
    'display_name', prof.display_name, 'bio', prof.bio, 'accent', prof.accent, 'has_avatar', prof.avatar_url is not null,
    'has_banner', prof.banner_url is not null, 'is_public', prof.is_public,
    'modules', public.ketvoice_profile_modules_for(prof.id, rel, prof.is_public), 'listening', shown);
end $$;
revoke all on function public.ketvoice_profile_view(text) from public, anon;
grant execute on function public.ketvoice_profile_view(text) to authenticated;
