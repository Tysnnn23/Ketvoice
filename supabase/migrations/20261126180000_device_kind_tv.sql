-- Ketvoice TV 1.0.0: « tv » becomes a device kind, for the connected devices list and the QR sign-in codes.
-- Only the allowed values of two check constraints grow; nothing else changes (RLS, limits, rows).
do $$
declare
  c record;
begin
  for c in
    select t.relname as tab, k.conname as name from pg_constraint k join pg_class t on t.oid = k.conrelid
    where t.relnamespace = 'public'::regnamespace and k.contype = 'c'
      and t.relname in ('ketvoice_devices', 'ketvoice_device_links')
      and pg_get_constraintdef(k.oid) like '%''quest''%' and pg_get_constraintdef(k.oid) like '%''pc''%'
  loop
    execute format('alter table public.%I drop constraint %I', c.tab, c.name);
  end loop;
end $$;

alter table public.ketvoice_devices add constraint ketvoice_devices_kind_check
  check (kind in ('phone', 'tablet', 'quest', 'pc', 'web', 'tv'));
alter table public.ketvoice_device_links add constraint ketvoice_device_links_device_kind_check
  check (device_kind in ('phone', 'tablet', 'quest', 'pc', 'web', 'tv'));
