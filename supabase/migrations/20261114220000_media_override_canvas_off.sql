-- A title's (or an album's) Canvas can be switched off without removing anything: the row then
-- says "no Canvas here", whatever the catalogue offers. Written only through ketvoice-content.
alter table public.ketvoice_media_overrides
  add column if not exists canvas_off boolean not null default false;
