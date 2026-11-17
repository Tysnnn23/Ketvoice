-- Shared playlists and public profiles for Ketvoice listener accounts (6.3).
--
-- Written to be re-run safely: tables and columns use IF NOT EXISTS, functions CREATE OR REPLACE,
-- policies and triggers are dropped before they are created, constraints are added only when missing.
-- Nothing here touches the publishing tables, their policies, the public media bucket or the verified
-- owner account (`konnektsupport@gmail.com`, reserved by 20261117120100_reserve_owner_email.sql): a
-- listener account still gains no publishing right, and no table below is granted to `anon`.
--
-- Contents
--   1. Shared playlists: playlists, members (owner / editor), tracks, invitation codes, join attempts.
--   2. Public profile: ketvoice_profiles.is_public / show_listening / avatar_ref, public playlists, the
--      read-only RPC ketvoice_public_profile(handle), a bucket for the public copy of a photo.
--
-- Avatars (the choice, documented):
--   * A private avatar lives in `ketvoice-avatars` under `<user id>/avatar-<ms>.jpg`. Its object path
--     contains the account's id, so it is NEVER returned by the public RPC.
--   * Another member of a shared playlist may read a co-member's photo (members already see each other's
--     ids): a select policy on `ketvoice-avatars` allows exactly the folders of people one shares a playlist
--     with, through ketvoice_can_view_avatar().
--   * A public profile shows a COPY of the photo that the owner uploads to `ketvoice-public-avatars` under
--     a random name (`<avatar_ref>.jpg`, avatar_ref being a random uuid unrelated to the user id). The RPC
--     returns avatar_ref only for a public profile; the select policy of that bucket lets authenticated
--     readers fetch an object only while the profile that owns it is public. Turning the profile private
--     therefore stops the photo being readable at once, even if the app could not delete the copy.
--   The caller downloads with its own session (`/storage/v1/object/authenticated/...`); there is no public
--   link and nothing to expire.

-- ============================================================================================================
-- 1. Shared playlists
-- ============================================================================================================
create table if not exists public.ketvoice_shared_playlists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(btrim(name)) between 1 and 80 and name !~ '[[:cntrl:]]'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists ketvoice_shared_playlists_owner on public.ketvoice_shared_playlists (owner_id);

create table if not exists public.ketvoice_shared_members (
  playlist_id uuid not null references public.ketvoice_shared_playlists(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('owner', 'editor')),
  joined_at timestamptz not null default now(),
  primary key (playlist_id, user_id)
);
create index if not exists ketvoice_shared_members_user on public.ketvoice_shared_members (user_id);
create unique index if not exists ketvoice_shared_members_one_owner on public.ketvoice_shared_members (playlist_id) where role = 'owner';

-- A title of a shared playlist: what the app needs to show it (the rest is looked up when it plays).
-- `pos` orders the list; the app places a title between two neighbours (fractions), so two people adding or
-- moving at once never renumber each other's rows. A title appears once per playlist.
create table if not exists public.ketvoice_shared_tracks (
  id uuid primary key default gen_random_uuid(),
  playlist_id uuid not null references public.ketvoice_shared_playlists(id) on delete cascade,
  track_id text not null check (char_length(track_id) between 1 and 256),
  title text not null check (char_length(title) between 1 and 300),
  artist text not null default '' check (char_length(artist) <= 300),
  album text not null default '' check (char_length(album) <= 300),
  cover_url text check (cover_url is null or (cover_url ~ '^https://' and char_length(cover_url) <= 1000)),
  duration_ms bigint not null default 0 check (duration_ms between 0 and 86400000),
  extra jsonb not null default '{}'::jsonb check (jsonb_typeof(extra) = 'object' and pg_column_size(extra) <= 2048),
  added_by uuid references auth.users(id) on delete set null,
  added_at timestamptz not null default now(),
  pos double precision not null check (pos between -1000000000 and 1000000000),
  unique (playlist_id, track_id)
);
create index if not exists ketvoice_shared_tracks_order on public.ketvoice_shared_tracks (playlist_id, pos, added_at);

-- Invitations: a short code the owner shows or shares. Created and consumed only by the functions below.
create table if not exists public.ketvoice_shared_invites (
  code text primary key check (code ~ '^[A-HJ-NP-Z2-9]{10}$'),
  playlist_id uuid not null references public.ketvoice_shared_playlists(id) on delete cascade,
  created_by uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  max_uses integer not null default 10 check (max_uses between 1 and 50),
  uses integer not null default 0,
  check (expires_at > created_at and expires_at <= created_at + interval '30 days'),
  check (uses between 0 and max_uses)
);
create index if not exists ketvoice_shared_invites_playlist on public.ketvoice_shared_invites (playlist_id);

-- Failed attempts to join with a code, to slow down anyone guessing codes. No policy and no grant: only
-- ketvoice_join_shared_playlist() reads and writes it.
create table if not exists public.ketvoice_shared_join_attempts (
  user_id uuid not null references auth.users(id) on delete cascade,
  at timestamptz not null default now()
);
create index if not exists ketvoice_shared_join_attempts_user on public.ketvoice_shared_join_attempts (user_id, at);

-- Helpers used by the policies. Security definer so that a policy on one table can look at another without
-- recursing through its policies; they only answer a yes/no about the caller.
create or replace function public.ketvoice_is_shared_member(p_playlist uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_shared_members m
    where m.playlist_id = p_playlist and m.user_id = (select auth.uid()))
$$;

create or replace function public.ketvoice_is_shared_owner(p_playlist uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_shared_playlists p
    where p.id = p_playlist and p.owner_id = (select auth.uid()))
$$;

-- Limits and bookkeeping ------------------------------------------------------------------------------------
create or replace function public.ketvoice_shared_playlists_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    if (select count(*) from public.ketvoice_shared_playlists where owner_id = new.owner_id) >= 20 then
      raise exception 'At most 20 shared playlists per account' using errcode = '54000';
    end if;
    new.created_at := now();
  end if;
  new.updated_at := now();
  return new;
end $$;
drop trigger if exists ketvoice_shared_playlists_guard on public.ketvoice_shared_playlists;
create trigger ketvoice_shared_playlists_guard before insert or update on public.ketvoice_shared_playlists
  for each row execute function public.ketvoice_shared_playlists_guard();

-- The creator becomes the owner member (the only way an owner row appears).
create or replace function public.ketvoice_shared_playlists_owner() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.ketvoice_shared_members (playlist_id, user_id, role) values (new.id, new.owner_id, 'owner')
  on conflict do nothing;
  return new;
end $$;
drop trigger if exists ketvoice_shared_playlists_owner on public.ketvoice_shared_playlists;
create trigger ketvoice_shared_playlists_owner after insert on public.ketvoice_shared_playlists
  for each row execute function public.ketvoice_shared_playlists_owner();

create or replace function public.ketvoice_shared_tracks_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    -- One writer at a time per playlist, so the 500-title limit holds under concurrent adds.
    perform pg_advisory_xact_lock(hashtext(new.playlist_id::text));
    if (select count(*) from public.ketvoice_shared_tracks where playlist_id = new.playlist_id) >= 500 then
      raise exception 'At most 500 titles per shared playlist' using errcode = '54000';
    end if;
    new.added_at := now();
  end if;
  return new;
end $$;
drop trigger if exists ketvoice_shared_tracks_guard on public.ketvoice_shared_tracks;
create trigger ketvoice_shared_tracks_guard before insert on public.ketvoice_shared_tracks
  for each row execute function public.ketvoice_shared_tracks_guard();

-- A change to the titles marks the playlist as changed (the app compares this to know what to re-read).
create or replace function public.ketvoice_shared_tracks_touch() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.ketvoice_shared_playlists set updated_at = now()
  where id = case when tg_op = 'DELETE' then old.playlist_id else new.playlist_id end;
  return null;
end $$;
drop trigger if exists ketvoice_shared_tracks_touch on public.ketvoice_shared_tracks;
create trigger ketvoice_shared_tracks_touch after insert or update or delete on public.ketvoice_shared_tracks
  for each row execute function public.ketvoice_shared_tracks_touch();

-- Trigger functions are never called directly.
revoke all on function public.ketvoice_shared_playlists_guard(), public.ketvoice_shared_playlists_owner(),
  public.ketvoice_shared_tracks_guard(), public.ketvoice_shared_tracks_touch() from public, anon, authenticated;

-- Row level security and grants --------------------------------------------------------------------------------
-- Only members read; any member (owner or editor) writes titles; only the owner renames and deletes; nobody
-- inserts a member row directly (a member appears through ketvoice_join_shared_playlist or as the owner
-- trigger). Grants are per column where a table may only be partly written.
alter table public.ketvoice_shared_playlists enable row level security;
alter table public.ketvoice_shared_members enable row level security;
alter table public.ketvoice_shared_tracks enable row level security;
alter table public.ketvoice_shared_invites enable row level security;
alter table public.ketvoice_shared_join_attempts enable row level security;

revoke all on public.ketvoice_shared_playlists, public.ketvoice_shared_members, public.ketvoice_shared_tracks,
  public.ketvoice_shared_invites, public.ketvoice_shared_join_attempts from public, anon, authenticated;
grant select, delete on public.ketvoice_shared_playlists to authenticated;
grant insert (id, owner_id, name), update (name) on public.ketvoice_shared_playlists to authenticated;
grant select, delete on public.ketvoice_shared_members to authenticated;
grant select, delete on public.ketvoice_shared_tracks to authenticated;
grant insert (id, playlist_id, track_id, title, artist, album, cover_url, duration_ms, extra, added_by, pos)
  on public.ketvoice_shared_tracks to authenticated;
grant update (pos) on public.ketvoice_shared_tracks to authenticated;
grant select, delete on public.ketvoice_shared_invites to authenticated;
grant all on public.ketvoice_shared_playlists, public.ketvoice_shared_members, public.ketvoice_shared_tracks,
  public.ketvoice_shared_invites, public.ketvoice_shared_join_attempts to service_role;

revoke all on function public.ketvoice_is_shared_member(uuid), public.ketvoice_is_shared_owner(uuid) from public, anon;
grant execute on function public.ketvoice_is_shared_member(uuid), public.ketvoice_is_shared_owner(uuid) to authenticated;

drop policy if exists ketvoice_shared_playlists_select on public.ketvoice_shared_playlists;
create policy ketvoice_shared_playlists_select on public.ketvoice_shared_playlists for select to authenticated
  using (owner_id = (select auth.uid()) or public.ketvoice_is_shared_member(id));
drop policy if exists ketvoice_shared_playlists_insert on public.ketvoice_shared_playlists;
create policy ketvoice_shared_playlists_insert on public.ketvoice_shared_playlists for insert to authenticated
  with check (owner_id = (select auth.uid()));
drop policy if exists ketvoice_shared_playlists_update on public.ketvoice_shared_playlists;
create policy ketvoice_shared_playlists_update on public.ketvoice_shared_playlists for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
drop policy if exists ketvoice_shared_playlists_delete on public.ketvoice_shared_playlists;
create policy ketvoice_shared_playlists_delete on public.ketvoice_shared_playlists for delete to authenticated
  using (owner_id = (select auth.uid()));

drop policy if exists ketvoice_shared_members_select on public.ketvoice_shared_members;
create policy ketvoice_shared_members_select on public.ketvoice_shared_members for select to authenticated
  using (public.ketvoice_is_shared_member(playlist_id));
-- Leave (one's own row) or, for the owner, remove someone; the owner row itself never goes this way.
drop policy if exists ketvoice_shared_members_delete on public.ketvoice_shared_members;
create policy ketvoice_shared_members_delete on public.ketvoice_shared_members for delete to authenticated
  using (role <> 'owner' and (user_id = (select auth.uid()) or public.ketvoice_is_shared_owner(playlist_id)));

drop policy if exists ketvoice_shared_tracks_select on public.ketvoice_shared_tracks;
create policy ketvoice_shared_tracks_select on public.ketvoice_shared_tracks for select to authenticated
  using (public.ketvoice_is_shared_member(playlist_id));
drop policy if exists ketvoice_shared_tracks_insert on public.ketvoice_shared_tracks;
create policy ketvoice_shared_tracks_insert on public.ketvoice_shared_tracks for insert to authenticated
  with check (public.ketvoice_is_shared_member(playlist_id) and added_by = (select auth.uid()));
drop policy if exists ketvoice_shared_tracks_update on public.ketvoice_shared_tracks;
create policy ketvoice_shared_tracks_update on public.ketvoice_shared_tracks for update to authenticated
  using (public.ketvoice_is_shared_member(playlist_id)) with check (public.ketvoice_is_shared_member(playlist_id));
drop policy if exists ketvoice_shared_tracks_delete on public.ketvoice_shared_tracks;
create policy ketvoice_shared_tracks_delete on public.ketvoice_shared_tracks for delete to authenticated
  using (public.ketvoice_is_shared_member(playlist_id));

-- The owner sees (and can revoke) the codes of their playlist; nobody inserts or edits one directly.
drop policy if exists ketvoice_shared_invites_select on public.ketvoice_shared_invites;
create policy ketvoice_shared_invites_select on public.ketvoice_shared_invites for select to authenticated
  using (public.ketvoice_is_shared_owner(playlist_id));
drop policy if exists ketvoice_shared_invites_delete on public.ketvoice_shared_invites;
create policy ketvoice_shared_invites_delete on public.ketvoice_shared_invites for delete to authenticated
  using (public.ketvoice_is_shared_owner(playlist_id));

-- Invitations ---------------------------------------------------------------------------------------------------
-- A new code for the caller's own playlist (at most 5 live codes; the oldest are dropped). Valid 7 days by
-- default (1 to 30), 10 uses. Returns {code, expires_at}.
create or replace function public.ketvoice_create_shared_invite(p_playlist uuid, p_days integer default 7) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  raw text;
  new_code text;
  expiry timestamptz := now() + make_interval(days => greatest(1, least(coalesce(p_days, 7), 30)));
  tries integer := 0;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if not exists (select 1 from public.ketvoice_shared_playlists p where p.id = p_playlist and p.owner_id = uid) then
    raise exception 'Only the owner can invite' using errcode = '42501';
  end if;
  delete from public.ketvoice_shared_invites i where i.expires_at < now() - interval '30 days';
  delete from public.ketvoice_shared_invites i where i.playlist_id = p_playlist and i.code in (
    select j.code from public.ketvoice_shared_invites j where j.playlist_id = p_playlist
    order by j.created_at desc offset 4);
  loop
    -- Ten symbols out of 32 (a byte modulo 32 is unbiased), from two random uuids (their first 12 hex digits
    -- come before the fixed version bits).
    raw := substr(replace(gen_random_uuid()::text, '-', ''), 1, 12) || substr(replace(gen_random_uuid()::text, '-', ''), 1, 12);
    new_code := '';
    for n in 0..9 loop
      new_code := new_code || substr(alphabet, (get_byte(decode(substr(raw, n * 2 + 1, 2), 'hex'), 0) % 32) + 1, 1);
    end loop;
    begin
      insert into public.ketvoice_shared_invites (code, playlist_id, created_by, expires_at)
      values (new_code, p_playlist, uid, expiry);
      exit;
    exception when unique_violation then
      tries := tries + 1;
      if tries > 5 then raise; end if;
    end;
  end loop;
  return jsonb_build_object('code', new_code, 'expires_at', expiry);
end $$;

-- Joining with a code. The only way to become an editor. Never raises for a wrong code (so that the failed
-- attempt is kept): it answers {status: 'invalid'}; after 8 wrong codes in 10 minutes it refuses for a while.
-- Statuses: joined, already, invalid, full. A code is valid until it expires or its uses are spent.
create or replace function public.ketvoice_join_shared_playlist(code text) returns jsonb
language plpgsql security definer set search_path = '' as $$
#variable_conflict use_column
declare
  uid uuid := auth.uid();
  clean text := upper(btrim(coalesce(code, '')));
  invite public.ketvoice_shared_invites%rowtype;
  target public.ketvoice_shared_playlists%rowtype;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  delete from public.ketvoice_shared_join_attempts a where a.user_id = uid and a.at < now() - interval '1 day';
  if (select count(*) from public.ketvoice_shared_join_attempts a
      where a.user_id = uid and a.at > now() - interval '10 minutes') >= 8 then
    raise exception 'Too many attempts, try again later' using errcode = '54000';
  end if;
  if clean !~ '^[A-HJ-NP-Z2-9]{10}$' then
    insert into public.ketvoice_shared_join_attempts (user_id) values (uid);
    return jsonb_build_object('status', 'invalid');
  end if;
  select i.* into invite from public.ketvoice_shared_invites i where i.code = clean for update;
  if not found or invite.expires_at <= now() then
    insert into public.ketvoice_shared_join_attempts (user_id) values (uid);
    return jsonb_build_object('status', 'invalid');
  end if;
  select p.* into target from public.ketvoice_shared_playlists p where p.id = invite.playlist_id;
  if not found then
    return jsonb_build_object('status', 'invalid');
  end if;
  if exists (select 1 from public.ketvoice_shared_members m where m.playlist_id = target.id and m.user_id = uid) then
    return jsonb_build_object('status', 'already', 'playlist_id', target.id, 'name', target.name);
  end if;
  if invite.uses >= invite.max_uses then
    return jsonb_build_object('status', 'invalid');
  end if;
  if (select count(*) from public.ketvoice_shared_members m where m.playlist_id = target.id) >= 20
     or (select count(*) from public.ketvoice_shared_members m where m.user_id = uid) >= 50 then
    return jsonb_build_object('status', 'full');
  end if;
  insert into public.ketvoice_shared_members (playlist_id, user_id, role) values (target.id, uid, 'editor');
  update public.ketvoice_shared_invites i set uses = i.uses + 1 where i.code = invite.code;
  return jsonb_build_object('status', 'joined', 'playlist_id', target.id, 'name', target.name);
end $$;

-- The members of a playlist the caller belongs to, with what the members sheet shows: name, handle, photo
-- (the caller may read a co-member's photo, see ketvoice_can_view_avatar), and whether they have a public
-- profile to open. Returns a JSON array ordered owner first.
create or replace function public.ketvoice_shared_playlist_members(p_playlist uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null or not exists (
      select 1 from public.ketvoice_shared_members m where m.playlist_id = p_playlist and m.user_id = auth.uid()) then
    raise exception 'Not a member' using errcode = '42501';
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
        'user_id', m.user_id, 'role', m.role, 'joined_at', m.joined_at,
        'display_name', coalesce(pr.display_name, ''), 'handle', pr.handle,
        'avatar_path', pr.avatar_url, 'is_public', coalesce(pr.is_public, false))
      order by (m.role = 'owner') desc, m.joined_at)
    from public.ketvoice_shared_members m
    left join public.ketvoice_profiles pr on pr.id = m.user_id
    where m.playlist_id = p_playlist), '[]'::jsonb);
end $$;

revoke all on function public.ketvoice_create_shared_invite(uuid, integer), public.ketvoice_join_shared_playlist(text),
  public.ketvoice_shared_playlist_members(uuid) from public, anon;
grant execute on function public.ketvoice_create_shared_invite(uuid, integer), public.ketvoice_join_shared_playlist(text),
  public.ketvoice_shared_playlist_members(uuid) to authenticated;

-- ============================================================================================================
-- 2. Public profile
-- ============================================================================================================
alter table public.ketvoice_profiles add column if not exists is_public boolean not null default false;
alter table public.ketvoice_profiles add column if not exists show_listening boolean not null default false;
-- Random name of the public copy of the photo (see the top of this file); not derived from the user id.
alter table public.ketvoice_profiles add column if not exists avatar_ref uuid;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'ketvoice_profiles_public_needs_handle'
                 and conrelid = 'public.ketvoice_profiles'::regclass) then
    alter table public.ketvoice_profiles add constraint ketvoice_profiles_public_needs_handle
      check (not is_public or handle is not null);
  end if;
end $$;

-- The playlists an owner chose to publish: a snapshot the owner refreshes, never a flag on a synced playlist.
create table if not exists public.ketvoice_public_playlists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  local_id text not null check (char_length(local_id) between 1 and 80),
  name text not null check (char_length(btrim(name)) between 1 and 80 and name !~ '[[:cntrl:]]'),
  cover_url text check (cover_url is null or (cover_url ~ '^https://' and char_length(cover_url) <= 1000)),
  tracks jsonb not null default '[]'::jsonb check (jsonb_typeof(tracks) = 'array' and jsonb_array_length(tracks) <= 200
    and pg_column_size(tracks) <= 262144),
  published_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner_id, local_id)
);

create or replace function public.ketvoice_public_playlists_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    -- Republishing a playlist (an upsert) is not a new one: only a new row counts against the limit.
    if not exists (select 1 from public.ketvoice_public_playlists p where p.owner_id = new.owner_id and p.local_id = new.local_id)
       and (select count(*) from public.ketvoice_public_playlists where owner_id = new.owner_id) >= 20 then
      raise exception 'At most 20 public playlists per account' using errcode = '54000';
    end if;
    new.published_at := now();
  else
    if new.owner_id <> old.owner_id then
      raise exception 'A public playlist keeps its owner' using errcode = '42501';
    end if;
    new.published_at := old.published_at;
  end if;
  new.updated_at := now();
  return new;
end $$;
revoke all on function public.ketvoice_public_playlists_guard() from public, anon, authenticated;
drop trigger if exists ketvoice_public_playlists_guard on public.ketvoice_public_playlists;
create trigger ketvoice_public_playlists_guard before insert or update on public.ketvoice_public_playlists
  for each row execute function public.ketvoice_public_playlists_guard();

alter table public.ketvoice_public_playlists enable row level security;
revoke all on public.ketvoice_public_playlists from public, anon, authenticated;
grant select, insert, update, delete on public.ketvoice_public_playlists to authenticated;
grant all on public.ketvoice_public_playlists to service_role;
-- Owner-only on the table itself; other people read these rows only through ketvoice_public_profile().
drop policy if exists ketvoice_public_playlists_own_select on public.ketvoice_public_playlists;
create policy ketvoice_public_playlists_own_select on public.ketvoice_public_playlists for select to authenticated
  using ((select auth.uid()) = owner_id);
drop policy if exists ketvoice_public_playlists_own_insert on public.ketvoice_public_playlists;
create policy ketvoice_public_playlists_own_insert on public.ketvoice_public_playlists for insert to authenticated
  with check ((select auth.uid()) = owner_id);
drop policy if exists ketvoice_public_playlists_own_update on public.ketvoice_public_playlists;
create policy ketvoice_public_playlists_own_update on public.ketvoice_public_playlists for update to authenticated
  using ((select auth.uid()) = owner_id) with check ((select auth.uid()) = owner_id);
drop policy if exists ketvoice_public_playlists_own_delete on public.ketvoice_public_playlists;
create policy ketvoice_public_playlists_own_delete on public.ketvoice_public_playlists for delete to authenticated
  using ((select auth.uid()) = owner_id);

-- What a profile page shows, for a handle. Read-only; returns null for an unknown handle AND for a private
-- profile (the same answer, so handles cannot be probed). Never returns the e-mail or the user id: only
-- {handle, display_name, avatar_ref, playlists: [{id, name, cover_url, tracks}], listening: {title, artist,
-- cover_url, playing} | null}. `listening` is present only when the owner switched « Montrer ce que j'écoute »
-- on and the title was written less than 30 minutes ago.
create or replace function public.ketvoice_public_profile(handle text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
#variable_conflict use_column
declare
  wanted text := lower(btrim(coalesce(handle, '')));
  prof public.ketvoice_profiles%rowtype;
  doc jsonb;
  seen timestamptz;
  entry jsonb;
  shown jsonb := null;
begin
  if auth.uid() is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if wanted !~ '^[a-z0-9_.]{3,24}$' then
    return null;
  end if;
  select p.* into prof from public.ketvoice_profiles p where p.handle = wanted and p.is_public;
  if not found then
    return null;
  end if;
  if prof.show_listening then
    select l.doc, l.updated_at into doc, seen from public.ketvoice_library l
    where l.user_id = prof.id and l.kind = 'now_playing';
    if found and seen > now() - interval '30 minutes' and jsonb_typeof(doc -> 'queue') = 'array'
       and (doc ->> 'index') ~ '^[0-9]{1,3}$' then
      entry := (doc -> 'queue') -> ((doc ->> 'index')::integer);
      if jsonb_typeof(entry) = 'object' and jsonb_typeof(entry -> 'title') = 'string' then
        shown := jsonb_build_object(
          'title', left(entry ->> 'title', 300), 'artist', left(coalesce(entry ->> 'artist', ''), 300),
          'cover_url', case when (entry ->> 'coverUrl') ~ '^https://' and char_length(entry ->> 'coverUrl') <= 1000
                            then entry ->> 'coverUrl' end,
          'playing', coalesce((doc ->> 'playing')::boolean, false));
      end if;
    end if;
  end if;
  return jsonb_build_object(
    'handle', prof.handle,
    'display_name', prof.display_name,
    'avatar_ref', prof.avatar_ref,
    'playlists', coalesce((
      select jsonb_agg(jsonb_build_object('id', pp.id, 'name', pp.name, 'cover_url', pp.cover_url, 'tracks', pp.tracks)
        order by pp.published_at desc)
      from public.ketvoice_public_playlists pp where pp.owner_id = prof.id), '[]'::jsonb),
    'listening', shown);
end $$;
revoke all on function public.ketvoice_public_profile(text) from public, anon;
grant execute on function public.ketvoice_public_profile(text) to authenticated;

-- Photos --------------------------------------------------------------------------------------------------------
-- (a) Co-members read each other's photos in the private bucket (members already see each other's ids).
create or replace function public.ketvoice_can_view_avatar(p_folder text) returns boolean
language sql stable security definer set search_path = '' as $$
  -- CASE, so the uuid cast only runs for a well-formed folder name.
  select case when p_folder ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then exists (
      select 1 from public.ketvoice_shared_members mine
      join public.ketvoice_shared_members theirs on theirs.playlist_id = mine.playlist_id
      where mine.user_id = (select auth.uid()) and theirs.user_id = p_folder::uuid)
    else false end
$$;
revoke all on function public.ketvoice_can_view_avatar(text) from public, anon;
grant execute on function public.ketvoice_can_view_avatar(text) to authenticated;

drop policy if exists ketvoice_avatars_comembers_select on storage.objects;
create policy ketvoice_avatars_comembers_select on storage.objects for select to authenticated
  using (bucket_id = 'ketvoice-avatars' and public.ketvoice_can_view_avatar((storage.foldername(name))[1]));

-- (b) The public copy: random name, readable by signed-in listeners only while its profile is public.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('ketvoice-public-avatars', 'ketvoice-public-avatars', false, 1048576, array['image/jpeg'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create or replace function public.ketvoice_owns_public_avatar(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_profiles p
    where p.id = (select auth.uid()) and p.avatar_ref is not null and p_name = p.avatar_ref::text || '.jpg')
$$;
create or replace function public.ketvoice_public_avatar_visible(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_profiles p
    where p.is_public and p.avatar_ref is not null and p_name = p.avatar_ref::text || '.jpg')
$$;
revoke all on function public.ketvoice_owns_public_avatar(text), public.ketvoice_public_avatar_visible(text) from public, anon;
grant execute on function public.ketvoice_owns_public_avatar(text), public.ketvoice_public_avatar_visible(text) to authenticated;

drop policy if exists ketvoice_public_avatars_select on storage.objects;
create policy ketvoice_public_avatars_select on storage.objects for select to authenticated
  using (bucket_id = 'ketvoice-public-avatars'
    and (public.ketvoice_public_avatar_visible(name) or public.ketvoice_owns_public_avatar(name)));
drop policy if exists ketvoice_public_avatars_insert on storage.objects;
create policy ketvoice_public_avatars_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'ketvoice-public-avatars' and public.ketvoice_owns_public_avatar(name));
drop policy if exists ketvoice_public_avatars_update on storage.objects;
create policy ketvoice_public_avatars_update on storage.objects for update to authenticated
  using (bucket_id = 'ketvoice-public-avatars' and public.ketvoice_owns_public_avatar(name))
  with check (bucket_id = 'ketvoice-public-avatars' and public.ketvoice_owns_public_avatar(name));
drop policy if exists ketvoice_public_avatars_delete on storage.objects;
create policy ketvoice_public_avatars_delete on storage.objects for delete to authenticated
  using (bucket_id = 'ketvoice-public-avatars' and public.ketvoice_owns_public_avatar(name));
