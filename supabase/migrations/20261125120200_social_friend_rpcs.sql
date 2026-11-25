-- Windows 2.0 social, part 3 of 9: asking, answering and ending friendships. Re-runnable.
-- Answers carry handles, display names and opaque refs (the friendship row's own id), never an account id or
-- an e-mail address. Statuses mirror the app's friends state machine (social/FriendStates.kt).

-- Asks a handle (« @name », any case, or as typed in a link) to be friends. Statuses: requested, pending (already
-- asked), accepted (they had asked first: now friends), friends (already), unavailable (unknown handle or a block
-- either way), self, needs_handle (the caller has no handle yet), limit (50 pending requests or 500 people).
create or replace function public.ketvoice_friend_request(handle text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  wanted text := public.ketvoice_clean_handle(handle);
  target uuid;
  pair public.ketvoice_friendships%rowtype;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if not exists (select 1 from public.ketvoice_profiles p where p.id = uid and p.handle is not null) then
    return jsonb_build_object('status', 'needs_handle');
  end if;
  select p.id into target from public.ketvoice_profiles p where p.handle = wanted;
  if target is null or public.ketvoice_blocked_between(uid, target) then
    return jsonb_build_object('status', 'unavailable');
  end if;
  if target = uid then
    return jsonb_build_object('status', 'self');
  end if;
  perform pg_advisory_xact_lock(hashtext('ketvoice_friend:' || least(uid, target)::text || greatest(uid, target)::text));
  select f.* into pair from public.ketvoice_friendships f
  where least(f.requester, f.addressee) = least(uid, target) and greatest(f.requester, f.addressee) = greatest(uid, target);
  if found then
    if pair.status = 'accepted' then
      return jsonb_build_object('status', 'friends', 'ref', pair.id);
    elsif pair.requester = uid then
      return jsonb_build_object('status', 'pending', 'ref', pair.id);
    end if;
    update public.ketvoice_friendships f set status = 'accepted', answered_at = now() where f.id = pair.id;
    return jsonb_build_object('status', 'accepted', 'ref', pair.id);
  end if;
  if (select count(*) from public.ketvoice_friendships f where f.requester = uid and f.status = 'pending') >= 50
     or (select count(*) from public.ketvoice_friendships f where f.requester = uid or f.addressee = uid) >= 500 then
    return jsonb_build_object('status', 'limit');
  end if;
  insert into public.ketvoice_friendships (requester, addressee) values (uid, target) returning id into pair.id;
  return jsonb_build_object('status', 'requested', 'ref', pair.id);
end $$;

-- The person asked accepts or declines a pending request (declining deletes it, without telling the other
-- person anything but that it is gone). Statuses: accepted, declined, not_found.
create or replace function public.ketvoice_friend_answer(p_ref uuid, p_accept boolean) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  gone integer := 0;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if coalesce(p_accept, false) then
    update public.ketvoice_friendships f set status = 'accepted', answered_at = now()
    where f.id = p_ref and f.addressee = uid and f.status = 'pending';
  else
    delete from public.ketvoice_friendships f where f.id = p_ref and f.addressee = uid and f.status = 'pending';
  end if;
  get diagnostics gone = row_count;
  return jsonb_build_object('status', case when gone = 0 then 'not_found' when p_accept then 'accepted' else 'declined' end);
end $$;

-- Either side ends it: cancels a request one sent, or ends a friendship. Statuses: removed, not_found.
create or replace function public.ketvoice_friend_remove(p_ref uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  gone integer := 0;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  delete from public.ketvoice_friendships f where f.id = p_ref and (f.requester = uid
    or (f.addressee = uid and f.status = 'accepted'));
  get diagnostics gone = row_count;
  return jsonb_build_object('status', case when gone > 0 then 'removed' else 'not_found' end);
end $$;

revoke all on function public.ketvoice_friend_request(text), public.ketvoice_friend_answer(uuid, boolean),
  public.ketvoice_friend_remove(uuid) from public, anon;
grant execute on function public.ketvoice_friend_request(text), public.ketvoice_friend_answer(uuid, boolean),
  public.ketvoice_friend_remove(uuid) to authenticated;
