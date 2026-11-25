-- Windows 2.0 social, part 8 of 9: what anyone, signed in or not, may read of a PUBLIC profile (the website's
-- /u/<handle> page and its link preview), and which picture the ketvoice-profile edge function may serve.
-- Re-runnable. A private or unknown handle answers null, the same answer, so handles cannot be probed beyond
-- what is already public. Never an account id, an e-mail address or an object path.

-- {handle, display_name, bio, accent, has_avatar, has_banner, playlists (how many public playlists),
--  modules: only those set to public ({top, pinned}), listening: only when « now » is public}.
-- Same name and answer as the website's proposed card (tools/proposed-public-profile-card.sql), plus the 2.0 fields.
create or replace function public.ketvoice_public_profile_card(handle text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  wanted text := public.ketvoice_clean_handle(handle);
  prof public.ketvoice_profiles%rowtype;
  shown jsonb;
begin
  select p.* into prof from public.ketvoice_profiles p where p.handle = wanted and p.is_public;
  if not found or (auth.uid() is not null and public.ketvoice_blocked_between(auth.uid(), prof.id)) then
    return null;
  end if;
  if exists (select 1 from public.ketvoice_profile_modules m where m.user_id = prof.id and m.module = 'now' and m.reach = 'public') then
    shown := public.ketvoice_listening_of(prof.id);
  end if;
  return jsonb_build_object('handle', prof.handle, 'display_name', left(coalesce(prof.display_name, ''), 60),
    'bio', prof.bio, 'accent', prof.accent, 'has_avatar', prof.avatar_url is not null,
    'has_banner', prof.banner_url is not null,
    'playlists', (select count(*) from public.ketvoice_public_playlists pp where pp.owner_id = prof.id),
    'modules', public.ketvoice_profile_modules_for(prof.id, 'stranger', true), 'listening', shown);
end $$;
revoke all on function public.ketvoice_public_profile_card(text) from public;
grant execute on function public.ketvoice_public_profile_card(text) to anon, authenticated;

-- For the edge function only (service role): the object path of a handle's photo or banner when p_viewer
-- (null for someone not signed in) may see that profile's identity: its owner, a friend, or anyone while the
-- profile is public. Null otherwise.
create or replace function public.ketvoice_profile_image(p_viewer uuid, p_handle text, p_kind text) returns text
language plpgsql stable security definer set search_path = '' as $$
declare
  prof public.ketvoice_profiles%rowtype;
  rel text;
begin
  if p_kind not in ('avatar', 'banner') then
    return null;
  end if;
  select p.* into prof from public.ketvoice_profiles p where p.handle = public.ketvoice_clean_handle(p_handle);
  if not found or (p_viewer is not null and public.ketvoice_blocked_between(p_viewer, prof.id)) then
    return null;
  end if;
  rel := case when prof.id = p_viewer then 'me' when public.ketvoice_friends_between(p_viewer, prof.id) then 'friend'
    else 'stranger' end;
  if rel = 'stranger' and not prof.is_public then
    return null;
  end if;
  return case p_kind when 'avatar' then prof.avatar_url else prof.banner_url end;
end $$;
revoke all on function public.ketvoice_profile_image(uuid, text, text) from public, anon, authenticated;
grant execute on function public.ketvoice_profile_image(uuid, text, text) to service_role;
