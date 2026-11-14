-- Artwork, Canvas and artist photos that the verified owner substitutes for catalogue
-- titles, albums and artists. Reversible: removing every file deletes the row and the
-- original artwork shows again. Service-role access only, through ketvoice-content.
create table if not exists public.ketvoice_media_overrides (
  kind text not null check (kind in ('track', 'album', 'artist')),
  key text not null check (char_length(key) between 1 and 160),
  label text not null default '' check (char_length(label) <= 300),
  cover_path text,
  canvas_path text,
  hero_path text,
  updated_by text not null,
  updated_at timestamptz not null default now(),
  primary key (kind, key)
);
alter table public.ketvoice_media_overrides enable row level security;
revoke all on public.ketvoice_media_overrides from anon, authenticated;
comment on table public.ketvoice_media_overrides is
  'Owner-curated artwork, Canvas and artist photos for catalogue items; service-only access via ketvoice-content.';
