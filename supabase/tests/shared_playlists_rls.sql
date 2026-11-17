-- Assertions for 20261118120000_shared_playlists_and_public_profiles.sql.
--
-- NOT RUN when it was written (no Postgres was available): run it once after the migration is applied:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/shared_playlists_rls.sql
-- It runs in ONE transaction that is rolled back at the end, so it leaves nothing behind: it creates three
-- throw-away accounts (alice, bob, carol), impersonates them with `set local role authenticated` and
-- `request.jwt.claims`, and stops with an exception naming the first assertion that fails. Every line that
-- prints « ok … » passed. Run it as the `postgres` role (it needs to insert into auth.users and
-- storage.objects, and to switch roles).

begin;

-- Two helpers, dropped with the rollback. They run as the caller (security invoker), so the expressions
-- passed to them are evaluated under the impersonated role and its row level security.
create function public.zz_ok(label text, passed boolean) returns void language plpgsql as $$
begin
  if passed is not true then
    raise exception 'FAILED: %', label;
  end if;
  raise notice 'ok  %', label;
end $$;

-- Runs a statement and checks it fails with the given SQLSTATE ('' = any error).
create function public.zz_fails(label text, stmt text, expected_state text default '') returns void language plpgsql as $$
begin
  begin
    execute stmt;
  exception when others then
    if expected_state <> '' and sqlstate <> expected_state then
      raise exception 'FAILED: % (expected %, got % %)', label, expected_state, sqlstate, sqlerrm;
    end if;
    raise notice 'ok  % (%)', label, sqlstate;
    return;
  end;
  raise exception 'FAILED: % (the statement succeeded)', label;
end $$;

-- Rows touched by a statement (for « update/delete affected nothing »).
create function public.zz_rows(stmt text) returns bigint language plpgsql as $$
declare n bigint;
begin
  execute stmt;
  get diagnostics n = row_count;
  return n;
end $$;

grant execute on function public.zz_ok(text, boolean), public.zz_fails(text, text, text), public.zz_rows(text) to anon, authenticated;

-- Accounts --------------------------------------------------------------------------------------------------
insert into auth.users (id, email) values
  ('aaaaaaaa-0000-4000-8000-000000000001', 'alice@rls-test.invalid'),
  ('bbbbbbbb-0000-4000-8000-000000000002', 'bob@rls-test.invalid'),
  ('cccccccc-0000-4000-8000-000000000003', 'carol@rls-test.invalid');
-- (the ketvoice_new_listener trigger made their profiles)
update public.ketvoice_profiles set display_name = 'Alice', handle = 'alice' where id = 'aaaaaaaa-0000-4000-8000-000000000001';
update public.ketvoice_profiles set display_name = 'Bob', handle = 'bob' where id = 'bbbbbbbb-0000-4000-8000-000000000002';

-- Nothing of this feature is reachable by `anon` ----------------------------------------------------------------
select public.zz_ok('anon holds no grant on the new tables', not exists (
  select 1 from information_schema.role_table_grants
  where grantee = 'anon' and table_schema = 'public'
    and table_name in ('ketvoice_shared_playlists', 'ketvoice_shared_members', 'ketvoice_shared_tracks',
                       'ketvoice_shared_invites', 'ketvoice_shared_join_attempts', 'ketvoice_public_playlists')));
select public.zz_ok('anon cannot execute the new functions', not exists (
  select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public'
    and p.proname in ('ketvoice_join_shared_playlist', 'ketvoice_create_shared_invite', 'ketvoice_public_profile',
                      'ketvoice_shared_playlist_members', 'ketvoice_is_shared_member', 'ketvoice_is_shared_owner')
    and has_function_privilege('anon', p.oid, 'execute')));
select public.zz_ok('the join function is security definer with an empty search_path', exists (
  select 1 from pg_proc p where p.proname = 'ketvoice_join_shared_playlist' and p.prosecdef
    and p.proconfig @> array['search_path=""']));

-- Alice creates a shared playlist ------------------------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);

insert into public.ketvoice_shared_playlists (id, owner_id, name)
values ('11111111-0000-4000-8000-00000000000a', 'aaaaaaaa-0000-4000-8000-000000000001', 'Road trip');
select public.zz_ok('the creator is the owner member', exists (
  select 1 from public.ketvoice_shared_members
  where playlist_id = '11111111-0000-4000-8000-00000000000a' and user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and role = 'owner'));
select public.zz_fails('nobody makes a playlist for someone else',
  $$insert into public.ketvoice_shared_playlists (owner_id, name) values ('bbbbbbbb-0000-4000-8000-000000000002', 'Not mine')$$, '42501');
select public.zz_fails('a name is 1 to 80 characters',
  $$insert into public.ketvoice_shared_playlists (owner_id, name) values ('aaaaaaaa-0000-4000-8000-000000000001', '')$$, '23514');
select public.zz_fails('no one inserts a member row directly (not even themselves)',
  $$insert into public.ketvoice_shared_members (playlist_id, user_id, role)
    values ('11111111-0000-4000-8000-00000000000a', 'aaaaaaaa-0000-4000-8000-000000000001', 'editor')$$, '42501');

insert into public.ketvoice_shared_tracks (id, playlist_id, track_id, title, artist, album, cover_url, added_by, pos)
values ('22222222-0000-4000-8000-000000000001', '11111111-0000-4000-8000-00000000000a', 'trk-1', 'Song one', 'Artist', 'Album',
        'https://example.com/c.jpg', 'aaaaaaaa-0000-4000-8000-000000000001', 1);
select public.zz_fails('a title is on a playlist once',
  $$insert into public.ketvoice_shared_tracks (playlist_id, track_id, title, added_by, pos)
    values ('11111111-0000-4000-8000-00000000000a', 'trk-1', 'Again', 'aaaaaaaa-0000-4000-8000-000000000001', 2)$$, '23505');
select public.zz_fails('a cover must be https',
  $$insert into public.ketvoice_shared_tracks (playlist_id, track_id, title, cover_url, added_by, pos)
    values ('11111111-0000-4000-8000-00000000000a', 'trk-x', 'T', 'http://example.com/c.jpg', 'aaaaaaaa-0000-4000-8000-000000000001', 2)$$, '23514');
select public.zz_fails('a title is at most 300 characters',
  format($f$insert into public.ketvoice_shared_tracks (playlist_id, track_id, title, added_by, pos)
    values ('11111111-0000-4000-8000-00000000000a', 'trk-y', %L, 'aaaaaaaa-0000-4000-8000-000000000001', 3)$f$, repeat('x', 301)), '23514');
select public.zz_fails('extra is limited to 2 KB',
  format($f$insert into public.ketvoice_shared_tracks (playlist_id, track_id, title, extra, added_by, pos)
    values ('11111111-0000-4000-8000-00000000000a', 'trk-z', 'T', %L::jsonb, 'aaaaaaaa-0000-4000-8000-000000000001', 3)$f$,
    jsonb_build_object('k', repeat('x', 3000))::text), '23514');

-- The invitation code ---------------------------------------------------------------------------------------------------
create temp table zz_code (code text);
grant all on zz_code to authenticated;
insert into zz_code select public.ketvoice_create_shared_invite('11111111-0000-4000-8000-00000000000a') ->> 'code';
select public.zz_ok('a code is ten symbols from the unambiguous alphabet',
  (select code ~ '^[A-HJ-NP-Z2-9]{10}$' from zz_code));
select public.zz_ok('the owner reads the code of the playlist', (select count(*) from public.ketvoice_shared_invites) = 1);

-- Carol (a stranger) -------------------------------------------------------------------------------------------------------
select set_config('request.jwt.claims', '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}', true);
select public.zz_ok('a stranger sees no playlist', (select count(*) from public.ketvoice_shared_playlists) = 0);
select public.zz_ok('a stranger sees no title', (select count(*) from public.ketvoice_shared_tracks) = 0);
select public.zz_ok('a stranger sees no member', (select count(*) from public.ketvoice_shared_members) = 0);
select public.zz_ok('a stranger sees no invitation code', (select count(*) from public.ketvoice_shared_invites) = 0);
select public.zz_fails('a stranger cannot add a title',
  $$insert into public.ketvoice_shared_tracks (playlist_id, track_id, title, added_by, pos)
    values ('11111111-0000-4000-8000-00000000000a', 'trk-c', 'Intruder', 'cccccccc-0000-4000-8000-000000000003', 9)$$, '42501');
select public.zz_fails('a stranger cannot add themselves as a member',
  $$insert into public.ketvoice_shared_members (playlist_id, user_id, role)
    values ('11111111-0000-4000-8000-00000000000a', 'cccccccc-0000-4000-8000-000000000003', 'editor')$$, '42501');
select public.zz_ok('a stranger cannot change or delete the playlist',
  public.zz_rows($$update public.ketvoice_shared_playlists set name = 'Mine' where id = '11111111-0000-4000-8000-00000000000a'$$) = 0
  and public.zz_rows($$delete from public.ketvoice_shared_playlists where id = '11111111-0000-4000-8000-00000000000a'$$) = 0);
select public.zz_fails('a stranger cannot invite', $$select public.ketvoice_create_shared_invite('11111111-0000-4000-8000-00000000000a')$$, '42501');
select public.zz_fails('a stranger cannot list the members', $$select public.ketvoice_shared_playlist_members('11111111-0000-4000-8000-00000000000a')$$, '42501');
select public.zz_ok('a wrong code does not join',
  public.ketvoice_join_shared_playlist('ZZZZZZZZZZ') ->> 'status' = 'invalid');
select public.zz_ok('a badly formed code does not join', public.ketvoice_join_shared_playlist('abc') ->> 'status' = 'invalid');
select public.zz_ok('still not a member after wrong codes', (select count(*) from public.ketvoice_shared_playlists) = 0);
-- Eight wrong codes in ten minutes lock the guessing out (two are already counted).
select public.ketvoice_join_shared_playlist('ZZZZZZZZZY');
select public.ketvoice_join_shared_playlist('ZZZZZZZZZX');
select public.ketvoice_join_shared_playlist('ZZZZZZZZZW');
select public.ketvoice_join_shared_playlist('ZZZZZZZZZV');
select public.ketvoice_join_shared_playlist('ZZZZZZZZZU');
select public.ketvoice_join_shared_playlist('ZZZZZZZZZT');
select public.zz_fails('too many wrong codes are refused for a while',
  $$select public.ketvoice_join_shared_playlist('ZZZZZZZZZS')$$, '54000');
select public.zz_fails('even the right code is refused while locked out', format($f$select public.ketvoice_join_shared_playlist(%L)$f$, (select code from zz_code)), '54000');

-- Bob joins with the code ------------------------------------------------------------------------------------------------------
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}', true);
select public.zz_ok('bob joins as an editor',
  public.ketvoice_join_shared_playlist((select code from zz_code)) ->> 'status' = 'joined');
select public.zz_ok('joining again changes nothing',
  public.ketvoice_join_shared_playlist((select lower(code) from zz_code)) ->> 'status' = 'already');
select public.zz_ok('bob is an editor, not an owner', exists (
  select 1 from public.ketvoice_shared_members where user_id = 'bbbbbbbb-0000-4000-8000-000000000002' and role = 'editor'));
select public.zz_ok('bob reads the playlist, its title and both members',
  (select count(*) from public.ketvoice_shared_playlists) = 1 and (select count(*) from public.ketvoice_shared_tracks) = 1
  and (select count(*) from public.ketvoice_shared_members) = 2);
select public.zz_ok('the members sheet lists the owner first, with names',
  (public.ketvoice_shared_playlist_members('11111111-0000-4000-8000-00000000000a') -> 0 ->> 'role') = 'owner'
  and (public.ketvoice_shared_playlist_members('11111111-0000-4000-8000-00000000000a') -> 1 ->> 'display_name') = 'Bob');
select public.zz_ok('an editor does not see the codes', (select count(*) from public.ketvoice_shared_invites) = 0);
select public.zz_fails('an editor cannot invite', $$select public.ketvoice_create_shared_invite('11111111-0000-4000-8000-00000000000a')$$, '42501');

insert into public.ketvoice_shared_tracks (id, playlist_id, track_id, title, artist, added_by, pos)
values ('22222222-0000-4000-8000-000000000002', '11111111-0000-4000-8000-00000000000a', 'trk-2', 'Song two', 'Artist',
        'bbbbbbbb-0000-4000-8000-000000000002', 2);
select public.zz_fails('an editor cannot sign someone else''s name on a title',
  $$insert into public.ketvoice_shared_tracks (playlist_id, track_id, title, added_by, pos)
    values ('11111111-0000-4000-8000-00000000000a', 'trk-3', 'Forged', 'aaaaaaaa-0000-4000-8000-000000000001', 3)$$, '42501');
select public.zz_ok('an editor moves a title',
  public.zz_rows($$update public.ketvoice_shared_tracks set pos = 0.5 where id = '22222222-0000-4000-8000-000000000002'$$) = 1);
select public.zz_fails('an editor cannot rewrite a title''s text',
  $$update public.ketvoice_shared_tracks set title = 'Hacked' where id = '22222222-0000-4000-8000-000000000001'$$, '42501');
select public.zz_fails('an editor cannot touch the playlist''s owner column (column grant)',
  $$update public.ketvoice_shared_playlists set owner_id = owner_id where id = '11111111-0000-4000-8000-00000000000a'$$, '42501');
select public.zz_ok('an editor cannot rename or delete the playlist',
  public.zz_rows($$update public.ketvoice_shared_playlists set name = 'Bob''s' where id = '11111111-0000-4000-8000-00000000000a'$$) = 0
  and public.zz_rows($$delete from public.ketvoice_shared_playlists where id = '11111111-0000-4000-8000-00000000000a'$$) = 0);
select public.zz_ok('an editor cannot remove the owner',
  public.zz_rows($$delete from public.ketvoice_shared_members where user_id = 'aaaaaaaa-0000-4000-8000-000000000001'$$) = 0);
select public.zz_ok('an editor deletes a title', public.zz_rows($$delete from public.ketvoice_shared_tracks where id = '22222222-0000-4000-8000-000000000002'$$) = 1);

-- Alice edits and manages --------------------------------------------------------------------------------------------------------------
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
select public.zz_ok('the owner renames', public.zz_rows($$update public.ketvoice_shared_playlists set name = 'Road trip 2' where id = '11111111-0000-4000-8000-00000000000a'$$) = 1);
select public.zz_ok('the owner row cannot be deleted by the owner (they delete the playlist instead)',
  public.zz_rows($$delete from public.ketvoice_shared_members where user_id = 'aaaaaaaa-0000-4000-8000-000000000001'$$) = 0);
select public.zz_ok('the owner removes a member', public.zz_rows($$delete from public.ketvoice_shared_members where user_id = 'bbbbbbbb-0000-4000-8000-000000000002'$$) = 1);
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}', true);
select public.zz_ok('a removed member sees nothing any more', (select count(*) from public.ketvoice_shared_playlists) = 0);

-- Expired and spent codes ------------------------------------------------------------------------------------------------------------------
reset role;
update public.ketvoice_shared_invites set created_at = now() - interval '2 days', expires_at = now() - interval '1 day';
delete from public.ketvoice_shared_join_attempts;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}', true);
select public.zz_ok('an expired code does not join', public.ketvoice_join_shared_playlist((select code from zz_code)) ->> 'status' = 'invalid');
reset role;
update public.ketvoice_shared_invites set created_at = now(), expires_at = now() + interval '1 day', max_uses = 1, uses = 1;
delete from public.ketvoice_shared_join_attempts;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}', true);
select public.zz_ok('a spent code does not join', public.ketvoice_join_shared_playlist((select code from zz_code)) ->> 'status' = 'invalid');
reset role;
update public.ketvoice_shared_invites set max_uses = 5, uses = 0;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}', true);
select public.zz_ok('a valid code joins again', public.ketvoice_join_shared_playlist((select code from zz_code)) ->> 'status' = 'joined');
select public.zz_ok('a member leaves on their own', public.zz_rows($$delete from public.ketvoice_shared_members where user_id = 'bbbbbbbb-0000-4000-8000-000000000002'$$) = 1);

-- Limits --------------------------------------------------------------------------------------------------------------------------------------
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
insert into public.ketvoice_shared_playlists (owner_id, name)
select 'aaaaaaaa-0000-4000-8000-000000000001', 'List ' || g from generate_series(1, 19) g;
select public.zz_fails('at most 20 shared playlists per owner',
  $$insert into public.ketvoice_shared_playlists (owner_id, name) values ('aaaaaaaa-0000-4000-8000-000000000001', 'One too many')$$, '54000');

-- Anonymous callers -----------------------------------------------------------------------------------------------------------------------------
reset role;
set local role anon;
select public.zz_fails('anon cannot read playlists', 'select * from public.ketvoice_shared_playlists', '42501');
select public.zz_fails('anon cannot join', $$select public.ketvoice_join_shared_playlist('AAAAAAAAAA')$$, '42501');
select public.zz_fails('anon cannot read a public profile', $$select public.ketvoice_public_profile('alice')$$, '42501');

-- Public profiles ----------------------------------------------------------------------------------------------------------------------------------
reset role;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
select public.zz_ok('a profile is private and shows no listening by default', (select not is_public and not show_listening
  from public.ketvoice_profiles where id = 'aaaaaaaa-0000-4000-8000-000000000001'));
select set_config('request.jwt.claims', '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}', true);
select public.zz_ok('a private profile cannot be opened (and answers like an unknown handle)',
  public.ketvoice_public_profile('alice') is null and public.ketvoice_public_profile('nobody-here') is null);
select public.zz_fails('a public profile needs a handle', $$update public.ketvoice_profiles set is_public = true where id = 'cccccccc-0000-4000-8000-000000000003'$$, '23514');
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
select public.zz_ok('another account''s profile cannot be changed',
  public.zz_rows($$update public.ketvoice_profiles set is_public = true where id = 'bbbbbbbb-0000-4000-8000-000000000002'$$) = 0);
update public.ketvoice_profiles set is_public = true where id = 'aaaaaaaa-0000-4000-8000-000000000001';
insert into public.ketvoice_public_playlists (owner_id, local_id, name, tracks)
values ('aaaaaaaa-0000-4000-8000-000000000001', 'local-1', 'Summer',
        '[{"id":"t1","title":"Song one","artist":"Artist","album":"A"}]'::jsonb);
select public.zz_fails('a public playlist holds at most 200 titles',
  $$insert into public.ketvoice_public_playlists (owner_id, local_id, name, tracks)
    values ('aaaaaaaa-0000-4000-8000-000000000001', 'big', 'Big', (select jsonb_agg(jsonb_build_object('id', g)) from generate_series(1, 201) g))$$, '23514');
-- A now-playing document, as NowPlayingSync writes it.
insert into public.ketvoice_library (user_id, kind, doc, device_id)
values ('aaaaaaaa-0000-4000-8000-000000000001', 'now_playing',
        '{"playing":true,"index":1,"queue":[{"id":"q0","title":"Before"},{"id":"q1","title":"Now playing","artist":"Band","coverUrl":"https://example.com/n.jpg"}]}'::jsonb,
        'device-0001');

select set_config('request.jwt.claims', '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}', true);
create temp table zz_profile (doc jsonb);
grant all on zz_profile to authenticated;
insert into zz_profile select public.ketvoice_public_profile('Alice');
select public.zz_ok('a public profile opens by handle (any case)', (select doc ->> 'handle' = 'alice' and doc ->> 'display_name' = 'Alice' from zz_profile));
select public.zz_ok('it lists the published playlist with its titles',
  (select jsonb_array_length(doc -> 'playlists') = 1 and doc -> 'playlists' -> 0 ->> 'name' = 'Summer'
     and jsonb_array_length(doc -> 'playlists' -> 0 -> 'tracks') = 1 from zz_profile));
select public.zz_ok('it shows no listening while the switch is off', (select doc -> 'listening' = 'null'::jsonb from zz_profile));
select public.zz_ok('it never carries the user id or an e-mail',
  (select position('aaaaaaaa-0000-4000-8000-000000000001' in doc::text) = 0 and position('@' in doc::text) = 0
     and position('email' in doc::text) = 0 and position('user_id' in doc::text) = 0 and position('owner_id' in doc::text) = 0 from zz_profile));
select public.zz_ok('the published rows themselves are not readable by others',
  (select count(*) from public.ketvoice_public_playlists) = 0);
select public.zz_fails('nobody else publishes for someone',
  $$insert into public.ketvoice_public_playlists (owner_id, local_id, name) values ('aaaaaaaa-0000-4000-8000-000000000001', 'x', 'Forged')$$, '42501');

select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
update public.ketvoice_profiles set show_listening = true where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select set_config('request.jwt.claims', '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}', true);
select public.zz_ok('with the switch on, only the current title shows',
  (public.ketvoice_public_profile('alice') -> 'listening' ->> 'title') = 'Now playing'
  and (public.ketvoice_public_profile('alice') -> 'listening' ->> 'artist') = 'Band'
  and not ((public.ketvoice_public_profile('alice') -> 'listening') ? 'queue'));
reset role;
-- The stamp trigger sets updated_at to now(): switch it off (inside this rolled-back transaction) to age the row.
alter table public.ketvoice_library disable trigger ketvoice_library_stamp;
update public.ketvoice_library set updated_at = now() - interval '2 hours' where user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and kind = 'now_playing';
alter table public.ketvoice_library enable trigger ketvoice_library_stamp;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}', true);
select public.zz_ok('an old title is not shown', public.ketvoice_public_profile('alice') -> 'listening' = 'null'::jsonb);

-- Photos: the public copy is readable only while the profile is public; co-members read private photos -------------------------------------
reset role;
update public.ketvoice_profiles set avatar_ref = 'dddddddd-0000-4000-8000-0000000000aa' where id = 'aaaaaaaa-0000-4000-8000-000000000001';
insert into storage.objects (bucket_id, name, owner) values
  ('ketvoice-public-avatars', 'dddddddd-0000-4000-8000-0000000000aa.jpg', 'aaaaaaaa-0000-4000-8000-000000000001'),
  ('ketvoice-avatars', 'aaaaaaaa-0000-4000-8000-000000000001/avatar-1700000000000.jpg', 'aaaaaaaa-0000-4000-8000-000000000001');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}', true);
select public.zz_ok('a stranger reads the public copy of a public profile', exists (
  select 1 from storage.objects where bucket_id = 'ketvoice-public-avatars' and name = 'dddddddd-0000-4000-8000-0000000000aa.jpg'));
select public.zz_ok('the profile page names the copy by a random reference, not by the user id',
  public.ketvoice_public_profile('alice') ->> 'avatar_ref' = 'dddddddd-0000-4000-8000-0000000000aa');
select public.zz_ok('a stranger does not read the private photo', not exists (
  select 1 from storage.objects where bucket_id = 'ketvoice-avatars'));
select public.zz_fails('nobody claims someone else''s photo reference',
  $$update public.ketvoice_profiles set avatar_ref = 'dddddddd-0000-4000-8000-0000000000aa' where id = 'cccccccc-0000-4000-8000-000000000003'$$, '23505');
select public.zz_fails('nobody uploads a copy under someone else''s reference',
  $$insert into storage.objects (bucket_id, name, owner) values ('ketvoice-public-avatars', 'dddddddd-0000-4000-8000-0000000000aa.jpg', 'cccccccc-0000-4000-8000-000000000003')$$, '42501');
-- Bob re-joins: he now shares a playlist with Alice and may read her private photo.
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
create temp table zz_code2 (code text);
grant all on zz_code2 to authenticated;
insert into zz_code2 select public.ketvoice_create_shared_invite((select id from public.ketvoice_shared_playlists where name = 'Road trip 2')) ->> 'code';
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}', true);
select public.ketvoice_join_shared_playlist((select code from zz_code2));
select public.zz_ok('a co-member reads the private photo', exists (
  select 1 from storage.objects where bucket_id = 'ketvoice-avatars' and name like 'aaaaaaaa-0000-4000-8000-000000000001/%'));
-- Alice goes private again: the public copy is no longer readable by strangers.
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
update public.ketvoice_profiles set is_public = false where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select set_config('request.jwt.claims', '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}', true);
select public.zz_ok('a private profile''s copy is unreadable and its page is closed',
  not exists (select 1 from storage.objects where bucket_id = 'ketvoice-public-avatars')
  and public.ketvoice_public_profile('alice') is null);
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}', true);
select public.zz_ok('the owner still reads their own copy', exists (select 1 from storage.objects where bucket_id = 'ketvoice-public-avatars'));

-- Deleting the owner removes the playlists he owned, and the others'' memberships go with the playlist -----------------------------------------
reset role;
delete from auth.users where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_ok('deleting the owner''s account removes the shared playlists with their titles and members', not exists (
  select 1 from public.ketvoice_shared_playlists where owner_id = 'aaaaaaaa-0000-4000-8000-000000000001')
  and not exists (select 1 from public.ketvoice_shared_members where user_id = 'aaaaaaaa-0000-4000-8000-000000000001')
  and not exists (select 1 from public.ketvoice_shared_tracks where playlist_id = '11111111-0000-4000-8000-00000000000a'));

select 'all shared-playlist and public-profile assertions passed' as result;
rollback;
