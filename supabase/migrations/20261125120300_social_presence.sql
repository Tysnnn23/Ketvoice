-- Windows 2.0 social, part 5 of 9: « Activité des amis », what friends listen to now. Re-runnable.
-- Opt-in on both counts: a row is written only while its owner has « Partager mon écoute » on
-- (ketvoice_profiles.share_listening, off by default), and it is readable only by the owner and by accounts
-- with an accepted friendship and no block, only while that switch is still on and the row is fresh (10 min).
-- `room` is the Ketogether room the owner is in (code-fingerprint), so a friend can « Rejoindre ».

create table if not exists public.ketvoice_presence (
  user_id uuid primary key references auth.users(id) on delete cascade,
  track jsonb check (track is null or (jsonb_typeof(track) = 'object' and pg_column_size(track) <= 4096)),
  playing boolean not null default false,
  room text check (room is null or room ~ '^[A-HJKMNP-Z2-9]{6}-[A-HJKMNP-Z2-9]{8}$'),
  updated_at timestamptz not null default now()
);

-- The title is cut to the fields a friend's app shows and plays (the activity feed's own cleaning); a local
-- file never leaves the computer.
create or replace function public.ketvoice_presence_stamp() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'UPDATE' and new.user_id <> old.user_id then
    raise exception 'A presence keeps its owner' using errcode = '42501';
  end if;
  if new.track is not null then
    if coalesce(new.track ->> 'id', '') like 'local:%' or coalesce(jsonb_typeof(new.track -> 'title'), '') <> 'string' then
      new.track := null;
    else
      new.track := public.ketvoice_activity_track(new.track);
    end if;
  end if;
  new.updated_at := now();
  return new;
end $$;
revoke all on function public.ketvoice_presence_stamp() from public, anon, authenticated;
drop trigger if exists ketvoice_presence_stamp on public.ketvoice_presence;
create trigger ketvoice_presence_stamp before insert or update on public.ketvoice_presence
  for each row execute function public.ketvoice_presence_stamp();

-- Whether the caller shares their listening (used by the write policies).
create or replace function public.ketvoice_shares_listening(p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_profiles p where p.id = p_user and p.share_listening and p.handle is not null)
$$;
revoke all on function public.ketvoice_shares_listening(uuid) from public, anon;
grant execute on function public.ketvoice_shares_listening(uuid) to authenticated;

alter table public.ketvoice_presence enable row level security;
revoke all on public.ketvoice_presence from public, anon, authenticated;
-- The owner writes their own row; nobody reads the account ids (reads go through ketvoice_friends_now).
grant select (track, playing, room, updated_at), insert (user_id, track, playing, room),
  update (track, playing, room), delete on public.ketvoice_presence to authenticated;
grant all on public.ketvoice_presence to service_role;

drop policy if exists ketvoice_presence_select on public.ketvoice_presence;
create policy ketvoice_presence_select on public.ketvoice_presence for select to authenticated
  using ((select auth.uid()) = user_id or (public.ketvoice_is_friend(user_id)
    and public.ketvoice_shares_listening(user_id) and updated_at > now() - interval '10 minutes'));
drop policy if exists ketvoice_presence_insert on public.ketvoice_presence;
create policy ketvoice_presence_insert on public.ketvoice_presence for insert to authenticated
  with check ((select auth.uid()) = user_id and public.ketvoice_shares_listening(user_id));
drop policy if exists ketvoice_presence_update on public.ketvoice_presence;
create policy ketvoice_presence_update on public.ketvoice_presence for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id and public.ketvoice_shares_listening(user_id));
drop policy if exists ketvoice_presence_delete on public.ketvoice_presence;
create policy ketvoice_presence_delete on public.ketvoice_presence for delete to authenticated
  using ((select auth.uid()) = user_id);

-- Turning « Partager mon écoute » off removes what was shared at once.
create or replace function public.ketvoice_presence_off() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if old.share_listening and not new.share_listening then
    delete from public.ketvoice_presence pr where pr.user_id = new.id;
  end if;
  return null;
end $$;
revoke all on function public.ketvoice_presence_off() from public, anon, authenticated;
drop trigger if exists ketvoice_presence_off on public.ketvoice_profiles;
create trigger ketvoice_presence_off after update of share_listening on public.ketvoice_profiles
  for each row execute function public.ketvoice_presence_off();
