-- Album canvas: the owner can attach one MP4 per album from Ketvoice Studio.
-- The file lives in the public media bucket; only its storage path is stored here.
alter table public.ketvoice_tracks add column if not exists canvas_path text;

comment on column public.ketvoice_tracks.canvas_path is
  'Owner-uploaded MP4 canvas shared by every track of the album; served publicly, never a provider credential.';
