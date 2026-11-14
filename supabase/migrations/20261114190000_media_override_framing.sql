-- Owner-chosen framing of an album's hero artwork (cover or Canvas): zoom and offset,
-- applied for every listener. Offsets are fractions of the visible frame.
alter table public.ketvoice_media_overrides
  add column if not exists frame_scale real check (frame_scale is null or (frame_scale >= 1 and frame_scale <= 3)),
  add column if not exists frame_x real check (frame_x is null or (frame_x >= -1 and frame_x <= 1)),
  add column if not exists frame_y real check (frame_y is null or (frame_y >= -1 and frame_y <= 1));
