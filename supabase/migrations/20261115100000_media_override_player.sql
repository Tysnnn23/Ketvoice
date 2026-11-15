-- What only the music player follows for a catalogue title or album: its own framing of the
-- artwork and its Canvas shown or hidden, whatever the album page does. Null = the player
-- follows the page. Written only through the ketvoice-content function (verified owner).
alter table public.ketvoice_media_overrides add column if not exists player jsonb;
