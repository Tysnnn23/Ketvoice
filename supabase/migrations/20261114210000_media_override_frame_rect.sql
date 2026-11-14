-- The album framing becomes a rectangle of the picture, with a turn and a mirror:
-- {cx, cy, w, h, rotation, flip}, in widths of the picture. It is validated by
-- ketvoice-content, the only writer. The zoom-and-offset columns of the first
-- framing (frame_scale, frame_x, frame_y) were never used and are left empty.
alter table public.ketvoice_media_overrides
  add column if not exists frame jsonb check (frame is null or jsonb_typeof(frame) = 'object');
