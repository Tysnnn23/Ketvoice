-- Friends feed for Ketvoice listener accounts (6.4): follow public profiles, see what they publish and
-- (when they chose « Montrer ce que j'écoute ») what they listen to, and react with a small fixed set of
-- emoji. Builds on 20261118120000_shared_playlists_and_public_profiles.sql.
--
-- Written to be re-run safely: tables, columns and indexes use IF NOT EXISTS, functions CREATE OR REPLACE,
-- triggers are dropped before they are created. Nothing here touches the publishing tables, their policies,
-- the public media bucket or the verified owner account (`konnektsupport@gmail.com`, reserved by
-- 20261117120100_reserve_owner_email.sql): a listener account still gains no publishing right.
--
-- Privacy rules (enforced here, not in the app):
--   * Only a PUBLIC profile can be followed (ketvoice_follow); following needs no approval, and the followed
--     person can remove a follower or block them. A block removes the follow both ways, the blocked person's
--     reactions, and hides the blocker's public profile from them (ketvoice_public_profile is replaced below).
--   * What a followed account shows is what its public profile already shows: its public playlists, and what
--     it listens to only while show_listening is on. « Publier mon activité » (share_activity, on by default,
--     only meaningful while the profile is public) stops activity being written, and hides what was written.
--   * The four tables below are granted to nobody but service_role: every read and write goes through the
--     security definer functions, which answer with handles, display names and avatar_ref, never an e-mail
--     address or a user id. Nothing is granted to anon. Row level security is on for every table.
--   * The activity is written only by the triggers below; rows older than 30 days are pruned on each write
--     and never returned.

-- ============================================================================================================
-- Tables
-- ============================================================================================================
alter table public.ketvoice_profiles add column if not exists share_activity boolean not null default true;

create table if not exists public.ketvoice_follows (
  id uuid primary key default gen_random_uuid(),
  follower uuid not null references auth.users(id) on delete cascade,
  followed uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (follower, followed),
  check (follower <> followed)
);
create index if not exists ketvoice_follows_followed on public.ketvoice_follows (followed, created_at desc);

create table if not exists public.ketvoice_blocks (
  id uuid primary key default gen_random_uuid(),
  blocker uuid not null references auth.users(id) on delete cascade,
  blocked uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (blocker, blocked),
  check (blocker <> blocked)
);
create index if not exists ketvoice_blocks_blocked on public.ketvoice_blocks (blocked);

-- One line of a friend's activity: a playlist published (`published`), titles added to a public playlist
-- (`added`, `titles` = how many) or a title listened to (`listening`, `track` = the title as the app plays it).
-- A playlist line follows the public playlist: unpublishing it deletes the line.
create table if not exists public.ketvoice_activity (
  id uuid primary key default gen_random_uuid(),
  actor uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('published', 'added', 'listening')),
  created_at timestamptz not null default clock_timestamp(),
  playlist_id uuid references public.ketvoice_public_playlists(id) on delete cascade,
  track jsonb check (track is null or (jsonb_typeof(track) = 'object' and pg_column_size(track) <= 4096)),
  titles integer check (titles is null or titles between 1 and 200),
  check ((kind = 'listening') = (track is not null)),
  check ((kind = 'listening') = (playlist_id is null))
);
create index if not exists ketvoice_activity_actor on public.ketvoice_activity (actor, created_at desc);
create index if not exists ketvoice_activity_age on public.ketvoice_activity (created_at);
create index if not exists ketvoice_activity_playlist on public.ketvoice_activity (playlist_id) where playlist_id is not null;

-- One reaction per person per line, from a fixed set (the app draws the emoji: heart ❤️, fire 🔥, clap 👏,
-- wow 😮, music 🎶).
create table if not exists public.ketvoice_reactions (
  activity_id uuid not null references public.ketvoice_activity(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  reaction text not null check (reaction in ('heart', 'fire', 'clap', 'wow', 'music')),
  created_at timestamptz not null default now(),
  primary key (activity_id, user_id)
);
create index if not exists ketvoice_reactions_user on public.ketvoice_reactions (user_id);

alter table public.ketvoice_follows enable row level security;
alter table public.ketvoice_blocks enable row level security;
alter table public.ketvoice_activity enable row level security;
alter table public.ketvoice_reactions enable row level security;
revoke all on public.ketvoice_follows, public.ketvoice_blocks, public.ketvoice_activity, public.ketvoice_reactions
  from public, anon, authenticated;
grant all on public.ketvoice_follows, public.ketvoice_blocks, public.ketvoice_activity, public.ketvoice_reactions to service_role;

-- ============================================================================================================
-- Internal helpers (called only by the functions below; executable by nobody else)
-- ============================================================================================================
-- A handle as typed (with or without « @ », any case), or null when it cannot be one.
create or replace function public.ketvoice_clean_handle(p_handle text) returns text
language sql immutable set search_path = '' as $$
  select case when lower(ltrim(btrim(coalesce(p_handle, '')), '@')) ~ '^[a-z0-9_.]{3,24}$'
    then lower(ltrim(btrim(coalesce(p_handle, '')), '@')) end
$$;

create or replace function public.ketvoice_blocked_between(p_a uuid, p_b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_blocks k
    where (k.blocker = p_a and k.blocked = p_b) or (k.blocker = p_b and k.blocked = p_a))
$$;

-- Whether an account's activity may be written and shown: a public profile with a handle that publishes it.
create or replace function public.ketvoice_activity_allowed(p_actor uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_profiles p
    where p.id = p_actor and p.is_public and p.share_activity and p.handle is not null)
$$;

-- Whether p_viewer may see (and react to) a line: they follow its author, whose profile is public and
-- publishes its activity, neither blocked the other, the line is less than 30 days old, and a listening line
-- only while its author shows what they listen to.
create or replace function public.ketvoice_can_see_activity(p_viewer uuid, p_activity uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.ketvoice_activity a
    join public.ketvoice_follows f on f.followed = a.actor and f.follower = p_viewer
    join public.ketvoice_profiles p on p.id = a.actor and p.is_public and p.share_activity and p.handle is not null
    where a.id = p_activity and a.created_at > now() - interval '30 days'
      and (a.kind <> 'listening' or p.show_listening)
      and not public.ketvoice_blocked_between(p_viewer, a.actor))
$$;

-- The title of a listening line: only the fields the app needs to show and play it, each cut to size.
create or replace function public.ketvoice_activity_track(e jsonb) returns jsonb
language sql immutable set search_path = '' as $$
  select jsonb_strip_nulls(jsonb_build_object(
    'id', left(e ->> 'id', 256),
    'title', left(e ->> 'title', 300),
    'artist', case when jsonb_typeof(e -> 'artist') = 'string' then left(e ->> 'artist', 300) else '' end,
    'album', case when jsonb_typeof(e -> 'album') = 'string' then left(e ->> 'album', 300) else '' end,
    'durationMs', case when (e ->> 'durationMs') ~ '^[0-9]{1,8}$' then (e ->> 'durationMs')::bigint end,
    'coverUrl', case when (e ->> 'coverUrl') ~ '^https://' and char_length(e ->> 'coverUrl') <= 1000 then e ->> 'coverUrl' end,
    'explicit', case when jsonb_typeof(e -> 'explicit') = 'boolean' then e -> 'explicit' end,
    'lyricsProvider', case when jsonb_typeof(e -> 'lyricsProvider') = 'string' then left(e ->> 'lyricsProvider', 40) end,
    'addon', case when jsonb_typeof(e -> 'addon') = 'string' then left(e ->> 'addon', 80) end,
    'addonId', case when jsonb_typeof(e -> 'addonId') = 'string' then left(e ->> 'addonId', 128) end,
    'videoId', case when jsonb_typeof(e -> 'videoId') = 'string' then left(e ->> 'videoId', 64) end,
    'spotifyId', case when jsonb_typeof(e -> 'spotifyId') = 'string' then left(e ->> 'spotifyId', 64) end,
    'isrc', case when jsonb_typeof(e -> 'isrc') = 'string' then left(e ->> 'isrc', 32) end,
    'albumId', case when jsonb_typeof(e -> 'albumId') = 'string' then left(e ->> 'albumId', 128) end,
    'artistId', case when jsonb_typeof(e -> 'artistId') = 'string' then left(e ->> 'artistId', 128) end))
$$;

-- Writes one line, prunes what is older than 30 days, and keeps at most 300 lines per account.
create or replace function public.ketvoice_activity_add(p_actor uuid, p_kind text, p_playlist uuid, p_track jsonb, p_titles integer)
returns void
language plpgsql security definer set search_path = '' as $$
begin
  delete from public.ketvoice_activity a where a.id in (
    select o.id from public.ketvoice_activity o where o.created_at < now() - interval '30 days' limit 500);
  insert into public.ketvoice_activity (actor, kind, playlist_id, track, titles)
  values (p_actor, p_kind, p_playlist, p_track, p_titles);
  delete from public.ketvoice_activity a where a.id in (
    select o.id from public.ketvoice_activity o where o.actor = p_actor order by o.created_at desc offset 300);
end $$;

revoke all on function public.ketvoice_clean_handle(text), public.ketvoice_blocked_between(uuid, uuid),
  public.ketvoice_activity_allowed(uuid), public.ketvoice_can_see_activity(uuid, uuid),
  public.ketvoice_activity_track(jsonb), public.ketvoice_activity_add(uuid, text, uuid, jsonb, integer)
  from public, anon, authenticated;

-- ============================================================================================================
-- Activity writers (triggers). A failure here never fails the listener's own write.
-- ============================================================================================================
-- « a publié la playlist X » on a new public playlist; « a ajouté N titres à X » when titles are added to one
-- (several adds within an hour make one line; adds just after publishing are part of the publication).
create or replace function public.ketvoice_public_playlists_activity() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  added integer;
  recent_id uuid;
  recent_kind text;
  recent_at timestamptz;
begin
  if not public.ketvoice_activity_allowed(new.owner_id) then
    return null;
  end if;
  if tg_op = 'INSERT' then
    perform public.ketvoice_activity_add(new.owner_id, 'published', new.id, null, nullif(least(jsonb_array_length(new.tracks), 200), 0));
    return null;
  end if;
  select count(*) into added from jsonb_array_elements(new.tracks) n
  where not exists (select 1 from jsonb_array_elements(old.tracks) o where o ->> 'id' = n ->> 'id');
  if added = 0 then
    return null;
  end if;
  select a.id, a.kind, a.created_at into recent_id, recent_kind, recent_at from public.ketvoice_activity a
  where a.actor = new.owner_id and a.playlist_id = new.id and a.created_at > now() - interval '1 hour'
  order by a.created_at desc limit 1;
  if recent_kind = 'published' and recent_at > now() - interval '10 minutes' then
    return null;
  end if;
  if recent_kind = 'added' then
    update public.ketvoice_activity a set titles = least(200, a.titles + added), created_at = clock_timestamp() where a.id = recent_id;
  else
    perform public.ketvoice_activity_add(new.owner_id, 'added', new.id, null, least(added, 200));
  end if;
  return null;
exception when others then
  return null;
end $$;
drop trigger if exists ketvoice_public_playlists_activity on public.ketvoice_public_playlists;
create trigger ketvoice_public_playlists_activity after insert or update of tracks on public.ketvoice_public_playlists
  for each row execute function public.ketvoice_public_playlists_activity();

-- « écoute X »: taken from what the account writes as now_playing, only while it plays, its profile is public,
-- shows what it listens to and publishes its activity; at most one line per 10 minutes, and the same title
-- again only after 3 hours.
create or replace function public.ketvoice_listening_activity() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  entry jsonb;
  last_at timestamptz;
  last_id text;
begin
  if new.kind <> 'now_playing' or not exists (select 1 from public.ketvoice_profiles p where p.id = new.user_id
      and p.is_public and p.share_activity and p.show_listening and p.handle is not null) then
    return null;
  end if;
  if coalesce(new.doc ->> 'playing', '') <> 'true' or coalesce(jsonb_typeof(new.doc -> 'queue'), '') <> 'array'
     or coalesce(new.doc ->> 'index', '') !~ '^[0-9]{1,3}$' then
    return null;
  end if;
  entry := (new.doc -> 'queue') -> ((new.doc ->> 'index')::integer);
  if coalesce(jsonb_typeof(entry), '') <> 'object' or coalesce(jsonb_typeof(entry -> 'title'), '') <> 'string'
     or coalesce(jsonb_typeof(entry -> 'id'), '') <> 'string' or (entry ->> 'id') like 'local:%'
     or char_length(entry ->> 'id') not between 1 and 256 or char_length(btrim(entry ->> 'title')) = 0 then
    return null;
  end if;
  select a.created_at, a.track ->> 'id' into last_at, last_id from public.ketvoice_activity a
  where a.actor = new.user_id and a.kind = 'listening' order by a.created_at desc limit 1;
  if last_at > now() - interval '10 minutes' or (last_id = entry ->> 'id' and last_at > now() - interval '3 hours') then
    return null;
  end if;
  perform public.ketvoice_activity_add(new.user_id, 'listening', null, public.ketvoice_activity_track(entry), null);
  return null;
exception when others then
  return null;
end $$;
drop trigger if exists ketvoice_listening_activity on public.ketvoice_library;
create trigger ketvoice_listening_activity after insert or update on public.ketvoice_library
  for each row when (new.kind = 'now_playing') execute function public.ketvoice_listening_activity();

revoke all on function public.ketvoice_public_playlists_activity(), public.ketvoice_listening_activity()
  from public, anon, authenticated;

-- ============================================================================================================
-- Following, followers and blocks
-- ============================================================================================================
-- Follows the public profile of a handle. Statuses: following, already, unavailable (unknown handle, private
-- profile or a block either way: the same answer, so nothing can be probed), self, needs_handle (the caller
-- has no handle yet: the person followed sees who follows them by name and handle), limit (500 follows).
create or replace function public.ketvoice_follow(handle text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  wanted text := public.ketvoice_clean_handle(handle);
  target uuid;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if not exists (select 1 from public.ketvoice_profiles p where p.id = uid and p.handle is not null) then
    return jsonb_build_object('status', 'needs_handle');
  end if;
  select p.id into target from public.ketvoice_profiles p where p.handle = wanted and p.is_public;
  if target is null or public.ketvoice_blocked_between(uid, target) then
    return jsonb_build_object('status', 'unavailable');
  end if;
  if target = uid then
    return jsonb_build_object('status', 'self');
  end if;
  if exists (select 1 from public.ketvoice_follows f where f.follower = uid and f.followed = target) then
    return jsonb_build_object('status', 'already');
  end if;
  perform pg_advisory_xact_lock(hashtext('ketvoice_follow:' || uid::text));
  if (select count(*) from public.ketvoice_follows f where f.follower = uid) >= 500 then
    return jsonb_build_object('status', 'limit');
  end if;
  insert into public.ketvoice_follows (follower, followed) values (uid, target) on conflict do nothing;
  return jsonb_build_object('status', 'following');
end $$;

-- Stops following a handle (whether or not that profile is still public). Statuses: removed, not_following.
create or replace function public.ketvoice_unfollow(handle text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  wanted text := public.ketvoice_clean_handle(handle);
  gone integer;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  delete from public.ketvoice_follows f where f.follower = uid
    and f.followed = (select p.id from public.ketvoice_profiles p where p.handle = wanted);
  get diagnostics gone = row_count;
  return jsonb_build_object('status', case when gone > 0 then 'removed' else 'not_following' end);
end $$;

-- The block itself (internal): no follow either way, no reaction of one on the other's activity.
create or replace function public.ketvoice_block_account(p_blocker uuid, p_blocked uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  if p_blocked is null or p_blocked = p_blocker then
    return jsonb_build_object('status', 'unavailable');
  end if;
  perform pg_advisory_xact_lock(hashtext('ketvoice_block:' || p_blocker::text));
  if not exists (select 1 from public.ketvoice_blocks k where k.blocker = p_blocker and k.blocked = p_blocked) then
    if (select count(*) from public.ketvoice_blocks k where k.blocker = p_blocker) >= 1000 then
      return jsonb_build_object('status', 'limit');
    end if;
    insert into public.ketvoice_blocks (blocker, blocked) values (p_blocker, p_blocked) on conflict do nothing;
  end if;
  delete from public.ketvoice_follows f where (f.follower = p_blocker and f.followed = p_blocked)
    or (f.follower = p_blocked and f.followed = p_blocker);
  delete from public.ketvoice_reactions r using public.ketvoice_activity a where r.activity_id = a.id
    and ((r.user_id = p_blocked and a.actor = p_blocker) or (r.user_id = p_blocker and a.actor = p_blocked));
  return jsonb_build_object('status', 'blocked');
end $$;
revoke all on function public.ketvoice_block_account(uuid, uuid) from public, anon, authenticated;

-- Blocks a handle from its profile page: a public profile, or someone who follows the caller (otherwise
-- unavailable, so private handles cannot be probed). Statuses: blocked, unavailable, limit.
create or replace function public.ketvoice_block(handle text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  wanted text := public.ketvoice_clean_handle(handle);
  target uuid;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  select p.id into target from public.ketvoice_profiles p where p.handle = wanted and (p.is_public or exists (
    select 1 from public.ketvoice_follows f where f.follower = p.id and f.followed = uid));
  return public.ketvoice_block_account(uid, target);
end $$;

-- The caller's lists, each line with an opaque `ref` (the follow or block row's own id, never a user id):
-- {following: [...], followers: [...], blocked: [...]}. A line shows a handle, a display name, avatar_ref only
-- for a public profile, whether that profile is public and, for a follower, whether the caller follows back.
create or replace function public.ketvoice_follow_lists() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'following', coalesce((select jsonb_agg(jsonb_build_object('ref', f.id, 'handle', p.handle, 'display_name', p.display_name,
        'avatar_ref', case when p.is_public then p.avatar_ref end, 'is_public', p.is_public, 'since', f.created_at)
        order by f.created_at desc)
      from public.ketvoice_follows f join public.ketvoice_profiles p on p.id = f.followed where f.follower = uid), '[]'::jsonb),
    'followers', coalesce((select jsonb_agg(jsonb_build_object('ref', f.id, 'handle', p.handle, 'display_name', p.display_name,
        'avatar_ref', case when p.is_public then p.avatar_ref end, 'is_public', p.is_public, 'since', f.created_at,
        'following', exists (select 1 from public.ketvoice_follows b where b.follower = uid and b.followed = f.follower))
        order by f.created_at desc)
      from public.ketvoice_follows f join public.ketvoice_profiles p on p.id = f.follower where f.followed = uid), '[]'::jsonb),
    'blocked', coalesce((select jsonb_agg(jsonb_build_object('ref', k.id, 'handle', p.handle, 'display_name', p.display_name,
        'since', k.created_at) order by k.created_at desc)
      from public.ketvoice_blocks k join public.ketvoice_profiles p on p.id = k.blocked where k.blocker = uid), '[]'::jsonb));
end $$;

-- What the lists' buttons do, by `ref`: unfollow (a line of following), remove (a follower), block (the
-- other person of a follow line), unblock (a line of blocked). Statuses: done, not_found, limit.
create or replace function public.ketvoice_follow_manage(p_ref uuid, p_action text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  gone integer := 0;
  other uuid;
  answer jsonb;
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if p_action = 'unfollow' then
    delete from public.ketvoice_follows f where f.id = p_ref and f.follower = uid;
    get diagnostics gone = row_count;
  elsif p_action = 'remove' then
    delete from public.ketvoice_follows f where f.id = p_ref and f.followed = uid;
    get diagnostics gone = row_count;
  elsif p_action = 'unblock' then
    delete from public.ketvoice_blocks k where k.id = p_ref and k.blocker = uid;
    get diagnostics gone = row_count;
  elsif p_action = 'block' then
    select case when f.follower = uid then f.followed else f.follower end into other
    from public.ketvoice_follows f where f.id = p_ref and (f.follower = uid or f.followed = uid);
    if other is null then
      return jsonb_build_object('status', 'not_found');
    end if;
    answer := public.ketvoice_block_account(uid, other);
    return jsonb_build_object('status', case when answer ->> 'status' = 'blocked' then 'done' else answer ->> 'status' end);
  else
    raise exception 'Unknown action' using errcode = '22023';
  end if;
  return jsonb_build_object('status', case when gone > 0 then 'done' else 'not_found' end);
end $$;

-- ============================================================================================================
-- Reactions and the feed
-- ============================================================================================================
-- One reaction of the caller on a line they can see; null or '' removes it. Answers {status, reactions, mine}
-- with status ok, removed or unavailable.
create or replace function public.ketvoice_react(p_activity uuid, p_reaction text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if coalesce(p_reaction, '') <> '' and p_reaction not in ('heart', 'fire', 'clap', 'wow', 'music') then
    raise exception 'Unknown reaction' using errcode = '22023';
  end if;
  if not public.ketvoice_can_see_activity(uid, p_activity) then
    return jsonb_build_object('status', 'unavailable');
  end if;
  if coalesce(p_reaction, '') = '' then
    delete from public.ketvoice_reactions r where r.activity_id = p_activity and r.user_id = uid;
  else
    insert into public.ketvoice_reactions (activity_id, user_id, reaction) values (p_activity, uid, p_reaction)
    on conflict (activity_id, user_id) do update set reaction = excluded.reaction, created_at = now();
  end if;
  return jsonb_build_object('status', case when coalesce(p_reaction, '') = '' then 'removed' else 'ok' end,
    'reactions', coalesce((select jsonb_object_agg(c.reaction, c.n) from (select r.reaction, count(*) as n
      from public.ketvoice_reactions r where r.activity_id = p_activity group by r.reaction) c), '{}'::jsonb),
    'mine', nullif(coalesce(p_reaction, ''), ''));
end $$;

-- The activity of the public profiles the caller follows, newest first: `before` (exclusive, null = now) and
-- `limit` (1 to 50, default 30) page through it. Each line: {id, kind, at, handle, display_name, avatar_ref,
-- playlist: {id, name, cover_url} | null, track | null, titles | null, reactions: {heart: n, …}, mine}.
create or replace function public.ketvoice_friends_feed(before timestamptz default null, "limit" integer default 30) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  n integer := greatest(1, least(coalesce("limit", 30), 50));
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  return coalesce((select jsonb_agg(s.line order by s.at desc, s.id desc) from (
    select a.id, a.created_at as at, jsonb_build_object('id', a.id, 'kind', a.kind, 'at', a.created_at,
      'handle', p.handle, 'display_name', p.display_name, 'avatar_ref', p.avatar_ref,
      'playlist', case when pp.id is null then null else jsonb_build_object('id', pp.id, 'name', pp.name,
        'cover_url', coalesce(pp.cover_url, case when (pp.tracks -> 0 ->> 'coverUrl') ~ '^https://'
          and char_length(pp.tracks -> 0 ->> 'coverUrl') <= 1000 then pp.tracks -> 0 ->> 'coverUrl' end)) end,
      'track', a.track, 'titles', a.titles,
      'reactions', coalesce((select jsonb_object_agg(c.reaction, c.n) from (select r.reaction, count(*) as n
        from public.ketvoice_reactions r where r.activity_id = a.id group by r.reaction) c), '{}'::jsonb),
      'mine', (select r.reaction from public.ketvoice_reactions r where r.activity_id = a.id and r.user_id = uid)) as line
    from public.ketvoice_follows f
    join public.ketvoice_profiles p on p.id = f.followed and p.is_public and p.share_activity and p.handle is not null
    join public.ketvoice_activity a on a.actor = f.followed
    left join public.ketvoice_public_playlists pp on pp.id = a.playlist_id
    where f.follower = uid and a.created_at > now() - interval '30 days'
      and (before is null or a.created_at < before)
      and (a.kind <> 'listening' or p.show_listening)
      and (a.kind = 'listening' or pp.id is not null)
      and not public.ketvoice_blocked_between(uid, f.followed)
    order by a.created_at desc, a.id desc
    limit n) s), '[]'::jsonb);
end $$;

revoke all on function public.ketvoice_follow(text), public.ketvoice_unfollow(text), public.ketvoice_block(text),
  public.ketvoice_follow_lists(), public.ketvoice_follow_manage(uuid, text), public.ketvoice_react(uuid, text),
  public.ketvoice_friends_feed(timestamptz, integer) from public, anon;
grant execute on function public.ketvoice_follow(text), public.ketvoice_unfollow(text), public.ketvoice_block(text),
  public.ketvoice_follow_lists(), public.ketvoice_follow_manage(uuid, text), public.ketvoice_react(uuid, text),
  public.ketvoice_friends_feed(timestamptz, integer) to authenticated;

-- ============================================================================================================
-- The public profile (replaces the 6.3 function): the same answer, plus `following` (whether the caller follows
-- it) and `is_me`; a profile whose owner blocked the caller, or that the caller blocked, answers null like a
-- private one.
-- ============================================================================================================
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
  if not found or public.ketvoice_blocked_between(auth.uid(), prof.id) then
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
    'listening', shown,
    'following', exists (select 1 from public.ketvoice_follows f where f.follower = auth.uid() and f.followed = prof.id),
    'is_me', prof.id = auth.uid());
end $$;
revoke all on function public.ketvoice_public_profile(text) from public, anon;
grant execute on function public.ketvoice_public_profile(text) to authenticated;
