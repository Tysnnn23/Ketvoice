-- Assertions for the Windows 2.0 « Connexions » migrations (20261125120001..: devices, device codes, Last.fm links).
--
-- Run on a throwaway PostgreSQL 16 after supabase/tests/supabase_stubs.sql and the migrations; as the `postgres`
-- role, in ONE transaction rolled back at the end. It stands in for Supabase's auth.sessions (GoTrue's table), makes
-- two accounts, impersonates them with `set local role authenticated` and `request.jwt.claims`, and stops at the first
-- assertion that fails. Every line that prints « ok … » passed.

begin;

create table if not exists auth.sessions (id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade);

create function public.zz_ok(label text, passed boolean) returns void language plpgsql as $$
begin
  if passed is not true then raise exception 'FAILED: %', label; end if;
  raise notice 'ok  %', label;
end $$;

create function public.zz_fails(label text, stmt text) returns void language plpgsql as $$
begin
  begin
    execute stmt;
  exception when others then
    raise notice 'ok  % (%)', label, sqlstate;
    return;
  end;
  raise exception 'FAILED: % (the statement succeeded)', label;
end $$;

create function public.zz_as(who text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', case who
    when 'alice' then '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}'
    when 'bob' then '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}'
    else '' end, true);
end $$;

grant execute on function public.zz_ok(text, boolean), public.zz_fails(text, text), public.zz_as(text) to authenticated, anon;

insert into auth.users (id, email) values
  ('aaaaaaaa-0000-4000-8000-000000000001', 'alice@rls-test.invalid'),
  ('bbbbbbbb-0000-4000-8000-000000000002', 'bob@rls-test.invalid');
insert into auth.sessions (id, user_id) values
  ('a0000000-0000-4000-8000-0000000000a1', 'aaaaaaaa-0000-4000-8000-000000000001'),
  ('a0000000-0000-4000-8000-0000000000a2', 'aaaaaaaa-0000-4000-8000-000000000001'),
  ('b0000000-0000-4000-8000-0000000000b1', 'bbbbbbbb-0000-4000-8000-000000000002');

-- Alice's two devices; Bob's one.
set local role authenticated;
select public.zz_as('alice');
insert into public.ketvoice_devices (user_id, device_id, name, kind, session_id, state)
  values ('aaaaaaaa-0000-4000-8000-000000000001', 'alice-pc-0001', 'DESKTOP-A', 'pc', 'a0000000-0000-4000-8000-0000000000a1', '{"seen": 1}');
insert into public.ketvoice_devices (user_id, device_id, name, kind, session_id, revoked_at)
  values ('aaaaaaaa-0000-4000-8000-000000000001', 'alice-phone-01', 'Pixel', 'phone', 'a0000000-0000-4000-8000-0000000000a2', now());
select public.zz_ok('a device cannot mark itself revoked on insert',
  (select revoked_at is null from public.ketvoice_devices where device_id = 'alice-phone-01'));
select public.zz_fails('a device row for another account is refused',
  $s$insert into public.ketvoice_devices (user_id, device_id, name, kind) values ('bbbbbbbb-0000-4000-8000-000000000002', 'bob-fake-0001', 'x', 'pc')$s$);
select public.zz_fails('a control character in a device name is refused',
  $s$insert into public.ketvoice_devices (user_id, device_id, name, kind) values ('aaaaaaaa-0000-4000-8000-000000000001', 'alice-bad-0001', e'x\u0007', 'pc')$s$);
select public.zz_as('bob');
insert into public.ketvoice_devices (user_id, device_id, name, kind, session_id)
  values ('bbbbbbbb-0000-4000-8000-000000000002', 'bob-pc-000001', 'BOB-PC', 'pc', 'b0000000-0000-4000-8000-0000000000b1');
select public.zz_ok('bob sees only his own device', (select count(*) = 1 from public.ketvoice_devices));
update public.ketvoice_devices set name = 'hijack' where device_id = 'alice-pc-0001';
select public.zz_ok('bob cannot change alice''s device', true);
select public.zz_as('alice');
select public.zz_ok('alice''s device kept its name', (select name = 'DESKTOP-A' from public.ketvoice_devices where device_id = 'alice-pc-0001'));
select public.zz_ok('alice sees her two devices', (select count(*) = 2 from public.ketvoice_devices));
select public.zz_fails('the revoke function is not for listeners',
  $s$select public.ketvoice_revoke_device('aaaaaaaa-0000-4000-8000-000000000001', 'alice-phone-01', null)$s$);
select public.zz_fails('device codes are not readable by listeners', 'select * from public.ketvoice_device_links');
select public.zz_fails('Last.fm session keys are not readable by listeners', 'select session_key from public.ketvoice_lastfm_links');
reset role;

-- The service role revokes alice's phone from her PC's session.
set local role service_role;
select public.zz_ok('revoking from the device''s own session is refused',
  public.ketvoice_revoke_device('aaaaaaaa-0000-4000-8000-000000000001', 'alice-pc-0001', 'a0000000-0000-4000-8000-0000000000a1') = 'self');
select public.zz_ok('an unknown device is not found',
  public.ketvoice_revoke_device('aaaaaaaa-0000-4000-8000-000000000001', 'bob-pc-000001', 'a0000000-0000-4000-8000-0000000000a1') = 'not_found');
select public.zz_ok('the phone is revoked',
  public.ketvoice_revoke_device('aaaaaaaa-0000-4000-8000-000000000001', 'alice-phone-01', 'a0000000-0000-4000-8000-0000000000a1') = 'revoked');
reset role;
select public.zz_ok('its session is gone', not exists (select 1 from auth.sessions where id = 'a0000000-0000-4000-8000-0000000000a2'));
select public.zz_ok('the other sessions stay', (select count(*) = 2 from auth.sessions));
select public.zz_ok('the row says revoked', (select revoked_at is not null from public.ketvoice_devices where device_id = 'alice-phone-01'));

set local role authenticated;
select public.zz_as('alice');
update public.ketvoice_devices set revoked_at = null, state = '{"seen": 2}' where device_id = 'alice-phone-01';
select public.zz_ok('a revoked device cannot clear the mark itself',
  (select revoked_at is not null from public.ketvoice_devices where device_id = 'alice-phone-01'));
update public.ketvoice_devices set session_id = 'a0000000-0000-4000-8000-0000000000a9' where device_id = 'alice-phone-01';
select public.zz_ok('signing in again (a new session) clears it',
  (select revoked_at is null from public.ketvoice_devices where device_id = 'alice-phone-01'));
select public.zz_fails('a device keeps its id', $s$update public.ketvoice_devices set device_id = 'alice-other-01' where device_id = 'alice-phone-01'$s$);
reset role;

-- Device codes: the shape of a code and the length of its life.
set local role service_role;
insert into public.ketvoice_device_links (code, secret_hash, device_name) values ('ABCD2345', repeat('a', 64), 'DESKTOP-A');
select public.zz_fails('a code with 0, O, 1 or I is refused',
  $s$insert into public.ketvoice_device_links (code, secret_hash) values ('ABCD0123', repeat('a', 64))$s$);
select public.zz_fails('a code cannot live past 10 minutes',
  $s$insert into public.ketvoice_device_links (code, secret_hash, expires_at) values ('ZZZZ2345', repeat('a', 64), now() + interval '1 hour')$s$);
insert into public.ketvoice_lastfm_links (user_id, username, session_key) values ('aaaaaaaa-0000-4000-8000-000000000001', 'alicefm', 'k-0123456789');
reset role;

-- Deleting an account takes its devices, codes and Last.fm link with it.
update public.ketvoice_device_links set approved_by = 'aaaaaaaa-0000-4000-8000-000000000001', approved_at = now() where code = 'ABCD2345';
delete from auth.users where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_ok('the account''s rows go with it',
  not exists (select 1 from public.ketvoice_devices where user_id = 'aaaaaaaa-0000-4000-8000-000000000001')
  and not exists (select 1 from public.ketvoice_lastfm_links) and not exists (select 1 from public.ketvoice_device_links));

rollback;
