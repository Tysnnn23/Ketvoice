-- Windows 2.0 social, part 9 of 9: a playlist shared by link (« Partager » in a playlist's menu). Re-runnable.
-- The owner writes a snapshot (name, cover, at most 100 titles) under a random id; whoever has the link reads
-- that snapshot through ketvoice_link_playlist(id), and nothing else: not the owner, not their other playlists.
-- It is not a public playlist (ketvoice_public_playlists): it shows on no profile and in no friend's activity.
-- The owner deletes it to stop the link working.

create table if not exists public.ketvoice_link_playlists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  local_id text not null check (char_length(local_id) between 1 and 80),
  name text not null check (char_length(btrim(name)) between 1 and 80 and name !~ '[[:cntrl:]]'),
  cover_url text check (cover_url is null or (cover_url ~ '^https://' and char_length(cover_url) <= 1000)),
  tracks jsonb not null default '[]'::jsonb check (jsonb_typeof(tracks) = 'array' and jsonb_array_length(tracks) <= 100
    and pg_column_size(tracks) <= 131072),
  updated_at timestamptz not null default now(),
  unique (owner_id, local_id)
);

create or replace function public.ketvoice_link_playlists_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    if not exists (select 1 from public.ketvoice_link_playlists l where l.owner_id = new.owner_id and l.local_id = new.local_id)
       and (select count(*) from public.ketvoice_link_playlists l where l.owner_id = new.owner_id) >= 50 then
      raise exception 'At most 50 shared playlist links per account' using errcode = '54000';
    end if;
  elsif new.owner_id <> old.owner_id or new.id <> old.id then
    raise exception 'A shared playlist keeps its owner' using errcode = '42501';
  end if;
  new.updated_at := now();
  return new;
end $$;
revoke all on function public.ketvoice_link_playlists_guard() from public, anon, authenticated;
drop trigger if exists ketvoice_link_playlists_guard on public.ketvoice_link_playlists;
create trigger ketvoice_link_playlists_guard before insert or update on public.ketvoice_link_playlists
  for each row execute function public.ketvoice_link_playlists_guard();

alter table public.ketvoice_link_playlists enable row level security;
revoke all on public.ketvoice_link_playlists from public, anon, authenticated;
grant select, insert, update, delete on public.ketvoice_link_playlists to authenticated;
grant all on public.ketvoice_link_playlists to service_role;
drop policy if exists ketvoice_link_playlists_own_select on public.ketvoice_link_playlists;
create policy ketvoice_link_playlists_own_select on public.ketvoice_link_playlists for select to authenticated
  using ((select auth.uid()) = owner_id);
drop policy if exists ketvoice_link_playlists_own_insert on public.ketvoice_link_playlists;
create policy ketvoice_link_playlists_own_insert on public.ketvoice_link_playlists for insert to authenticated
  with check ((select auth.uid()) = owner_id);
drop policy if exists ketvoice_link_playlists_own_update on public.ketvoice_link_playlists;
create policy ketvoice_link_playlists_own_update on public.ketvoice_link_playlists for update to authenticated
  using ((select auth.uid()) = owner_id) with check ((select auth.uid()) = owner_id);
drop policy if exists ketvoice_link_playlists_own_delete on public.ketvoice_link_playlists;
create policy ketvoice_link_playlists_own_delete on public.ketvoice_link_playlists for delete to authenticated
  using ((select auth.uid()) = owner_id);

-- The snapshot behind a link: {name, cover_url, tracks: [{id, title, artist, album, durationMs, coverUrl}]}, or null.
create or replace function public.ketvoice_link_playlist(p_id uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('name', l.name, 'cover_url', l.cover_url, 'updated_at', l.updated_at,
    'tracks', coalesce((select jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
        'id', left(t ->> 'id', 256), 'title', left(t ->> 'title', 300), 'artist', left(coalesce(t ->> 'artist', ''), 300),
        'album', left(coalesce(t ->> 'album', ''), 300),
        'durationMs', case when (t ->> 'durationMs') ~ '^[0-9]{1,8}$' then (t ->> 'durationMs')::bigint end,
        'coverUrl', case when (t ->> 'coverUrl') ~ '^https://' and char_length(t ->> 'coverUrl') <= 1000 then t ->> 'coverUrl' end))
        order by n)
      from jsonb_array_elements(l.tracks) with ordinality as e(t, n)
      where jsonb_typeof(t) = 'object' and jsonb_typeof(t -> 'title') = 'string' and coalesce(t ->> 'id', '') not like 'local:%'),
      '[]'::jsonb))
  from public.ketvoice_link_playlists l where l.id = p_id
$$;
revoke all on function public.ketvoice_link_playlist(uuid) from public;
grant execute on function public.ketvoice_link_playlist(uuid) to anon, authenticated;
