-- Windows 2.0 social, part 4 of 9: the caller's friends and requests, for the « Amis » lists. Re-runnable.
-- Handles, display names and opaque refs only (see part 3).

-- The caller's friends, the requests they received and the ones they sent:
-- {friends: [...], incoming: [...], outgoing: [...]}, each line {ref, handle, display_name, accent, has_avatar, since}.
create or replace function public.ketvoice_friend_lists() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  return (select jsonb_build_object(
      'friends', coalesce(jsonb_agg(s.line order by s.at desc) filter (where s.kind = 'friends'), '[]'::jsonb),
      'incoming', coalesce(jsonb_agg(s.line order by s.at desc) filter (where s.kind = 'incoming'), '[]'::jsonb),
      'outgoing', coalesce(jsonb_agg(s.line order by s.at desc) filter (where s.kind = 'outgoing'), '[]'::jsonb))
    from (select case when f.status = 'accepted' then 'friends' when f.addressee = uid then 'incoming' else 'outgoing' end as kind,
        coalesce(f.answered_at, f.created_at) as at,
        jsonb_build_object('ref', f.id, 'handle', p.handle, 'display_name', p.display_name, 'accent', p.accent,
          'has_avatar', p.avatar_url is not null, 'since', coalesce(f.answered_at, f.created_at)) as line
      from public.ketvoice_friendships f
      join public.ketvoice_profiles p on p.id = case when f.requester = uid then f.addressee else f.requester end
      where (f.requester = uid or f.addressee = uid) and p.handle is not null
        and not public.ketvoice_blocked_between(uid, p.id)) s);
end $$;
revoke all on function public.ketvoice_friend_lists() from public, anon;
grant execute on function public.ketvoice_friend_lists() to authenticated;
