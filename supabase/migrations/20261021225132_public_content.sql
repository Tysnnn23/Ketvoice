-- Public catalog/lyric revisions. Only the verified publisher broker may write.
create table public.ketvoice_tracks (
  id uuid primary key default gen_random_uuid(),
  title text not null check (length(title) between 1 and 200),
  artist text not null check (length(artist) between 1 and 200),
  album text not null default '' check (length(album) <= 200),
  duration_ms bigint not null default 0 check (duration_ms between 0 and 86400000),
  audio_path text not null unique,
  cover_path text,
  format text not null check (format in ('mp3','m4a','aac','flac','wav','ogg','opus')),
  bytes bigint not null check (bytes between 1 and 26214400),
  published boolean not null default true,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create table public.ketvoice_lyrics (
  id uuid primary key default gen_random_uuid(),
  track_id text not null check (length(track_id) between 1 and 180),
  recording_key text not null check (length(recording_key) between 3 and 600),
  title text not null check (length(title) between 1 and 200),
  artist text not null check (length(artist) between 1 and 200),
  duration_ms bigint not null default 0 check (duration_ms between 0 and 86400000),
  isrc text check (isrc ~ '^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$'),
  format text not null check (format in ('ttml','lrc')),
  content text not null check (octet_length(content) between 1 and 1048576),
  published boolean not null default true,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create index ketvoice_lyrics_track_idx on public.ketvoice_lyrics(track_id, created_at desc) where published;
create index ketvoice_lyrics_recording_idx on public.ketvoice_lyrics(recording_key, created_at desc) where published;
create index ketvoice_lyrics_isrc_idx on public.ketvoice_lyrics(isrc) where published and isrc is not null;
create index ketvoice_tracks_creator_idx on public.ketvoice_tracks(created_by);
create index ketvoice_lyrics_creator_idx on public.ketvoice_lyrics(created_by);
alter table public.ketvoice_tracks enable row level security;
alter table public.ketvoice_lyrics enable row level security;
revoke all on public.ketvoice_tracks, public.ketvoice_lyrics from anon, authenticated;
grant select on public.ketvoice_tracks, public.ketvoice_lyrics to anon, authenticated;
grant all on public.ketvoice_tracks, public.ketvoice_lyrics to service_role;
create policy published_tracks on public.ketvoice_tracks for select to anon, authenticated using (published);
create policy published_lyrics on public.ketvoice_lyrics for select to anon, authenticated using (published);

create function public.ketvoice_search_catalog(p_query text)
returns setof public.ketvoice_tracks language sql stable security invoker set search_path = '' as $$
  select * from public.ketvoice_tracks
  where published and length(trim(p_query)) between 2 and 160
    and position(lower(trim(p_query)) in lower(title || ' ' || artist || ' ' || album)) > 0
  order by created_at desc limit 30;
$$;
revoke all on function public.ketvoice_search_catalog(text) from public;
grant execute on function public.ketvoice_search_catalog(text) to anon, authenticated, service_role;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('ketvoice-public-media', 'ketvoice-public-media', true, 26214400,
  array['audio/mpeg','audio/mp4','audio/aac','audio/flac','audio/wav','audio/ogg','image/jpeg','image/png','image/webp']);
-- Intentionally NO client INSERT/UPDATE/DELETE policies on storage.objects.
