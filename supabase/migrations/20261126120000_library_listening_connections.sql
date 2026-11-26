-- 9.2 « tout synchroniser »: two more kinds in the listener's own synced library.
--   listening    recently played and listening statistics, each device under its own `dev:<device id>:` keys
--                (counters are summed between devices by the apps, never merged on the server);
--   connections  the « show what I listen to on Discord » choice and, per device, whether Discord is linked there
--                and under which name (never a token: Discord's tokens stay on the device that holds them).
-- Nothing else changes: the rows stay owner-only (RLS policies of ketvoice_library), the 4 MB document limit and
-- the server-counted revision apply as before, and the friends feed trigger still reacts to `now_playing` only.
do $$
declare
  existing text;
begin
  for existing in
    select c.conname from pg_constraint c
    where c.conrelid = 'public.ketvoice_library'::regclass and c.contype = 'c'
      and pg_get_constraintdef(c.oid) like '%kind%' and pg_get_constraintdef(c.oid) like '%now_playing%'
  loop
    execute format('alter table public.ketvoice_library drop constraint %I', existing);
  end loop;
end $$;

alter table public.ketvoice_library add constraint ketvoice_library_kind_check
  check (kind in ('favorites', 'playlists', 'artists', 'settings', 'now_playing', 'listening', 'connections'));
