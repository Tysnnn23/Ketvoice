-- Assertions for the Windows 2.0 social migrations (20261125120000 … 20261125120600): profile, modules,
-- friendships, « Activité des amis », the public card, profile pictures for the edge function, playlist links.
--
-- Run on a database where the listener-account migrations (20261117120000, 20261117120100, 20261118120000,
-- 20261119120000) and the nine social parts are applied; locally, on a throwaway PostgreSQL 16, after
-- supabase/tests/supabase_stubs.sql:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/social_rls.sql
-- ONE transaction, rolled back at the end: four throw-away accounts (alice, bob, carol, dave), impersonated with
-- `set local role` and `request.jwt.claims`. It stops at the first failing assertion; « ok … » lines passed.
-- Run it as `postgres`.

begin;

create function public.zz_ok(label text, passed boolean) returns void language plpgsql as $$
begin
  if passed is not true then raise exception 'FAILED: %', label; end if;
  raise notice 'ok  %', label;
end $$;

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

create function public.zz_as(who text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', case who
    when 'alice' then '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}'
    when 'bob' then '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}'
    when 'carol' then '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}'
    when 'dave' then '{"sub":"dddddddd-0000-4000-8000-000000000004","role":"authenticated"}'
    else '' end, true);
end $$;

-- Nothing answered to someone may hold an account id, an address or an object path.
create function public.zz_clean(answer jsonb) returns boolean language sql as $$
  select coalesce(answer::text, '') !~ '(aaaaaaaa|bbbbbbbb|cccccccc|dddddddd)-0000' and coalesce(answer::text, '') !~ 'rls-test'
    and coalesce(answer::text, '') !~ '(avatar|banner)-[0-9]' and coalesce(answer::text, '') !~ '"(user_id|requester|addressee|owner_id|email|avatar_url|banner_url)"'
$$;

create function public.zz_ref(answer jsonb) returns uuid language sql as $$ select (answer ->> 'ref')::uuid $$;

grant execute on function public.zz_ok(text, boolean), public.zz_fails(text, text, text), public.zz_as(text),
  public.zz_clean(jsonb), public.zz_ref(jsonb) to anon, authenticated;
create temp table zz (k text primary key, v text);
grant all on zz to anon, authenticated;

insert into auth.users (id, email) values
  ('aaaaaaaa-0000-4000-8000-000000000001', 'alice@rls-test.invalid'),
  ('bbbbbbbb-0000-4000-8000-000000000002', 'bob@rls-test.invalid'),
  ('cccccccc-0000-4000-8000-000000000003', 'carol@rls-test.invalid'),
  ('dddddddd-0000-4000-8000-000000000004', 'dave@rls-test.invalid');
update public.ketvoice_profiles set display_name = 'Alice', handle = 'alice', avatar_url = 'aaaaaaaa-0000-4000-8000-000000000001/avatar-1764000000000.jpg'
  where id = 'aaaaaaaa-0000-4000-8000-000000000001';
update public.ketvoice_profiles set display_name = 'Bob', handle = 'bob', is_public = true where id = 'bbbbbbbb-0000-4000-8000-000000000002';
update public.ketvoice_profiles set display_name = 'Carol', handle = 'carol' where id = 'cccccccc-0000-4000-8000-000000000003';
update public.ketvoice_profiles set display_name = 'Dave' where id = 'dddddddd-0000-4000-8000-000000000004';

-- Shape and defaults ---------------------------------------------------------------------------------------------
select public.zz_ok('row level security is on for every new table', (select bool_and(c.relrowsecurity) from pg_class c
  where c.oid in ('public.ketvoice_profile_modules'::regclass, 'public.ketvoice_friendships'::regclass,
                  'public.ketvoice_presence'::regclass, 'public.ketvoice_link_playlists'::regclass)));
select public.zz_ok('anon holds no grant on the new tables', not exists (select 1 from information_schema.role_table_grants
  where grantee in ('anon', 'PUBLIC') and table_schema = 'public' and table_name in ('ketvoice_profile_modules',
    'ketvoice_friendships', 'ketvoice_presence', 'ketvoice_link_playlists')));
select public.zz_ok('« Partager mon écoute » is off by default', not (select bool_or(share_listening) from public.ketvoice_profiles));
select public.zz_ok('the internal helpers are not callable by listeners', not exists (select 1 from pg_proc p
  where p.pronamespace = 'public'::regnamespace and p.proname in ('ketvoice_friends_between', 'ketvoice_listening_of',
    'ketvoice_profile_modules_for', 'ketvoice_profile_image', 'ketvoice_blocks_unfriend', 'ketvoice_presence_stamp',
    'ketvoice_presence_off', 'ketvoice_link_playlists_guard', 'ketvoice_profile_modules_stamp')
    and (has_function_privilege('authenticated', p.oid, 'execute') or has_function_privilege('anon', p.oid, 'execute'))));
select public.zz_ok('every security definer function of the feature has an empty search_path', not exists (select 1 from pg_proc p
  where p.pronamespace = 'public'::regnamespace and p.prosecdef and (p.proname like 'ketvoice_friend%' or p.proname like 'ketvoice_profile%'
    or p.proname like 'ketvoice_presence%' or p.proname like 'ketvoice_link_playlist%' or p.proname in ('ketvoice_is_friend',
    'ketvoice_shares_listening', 'ketvoice_listening_of', 'ketvoice_public_profile_card', 'ketvoice_blocks_unfriend'))
    and not (p.proconfig @> array['search_path=""'])));
select public.zz_ok('the visibility rule is the app''s (ProfileRules.canSee)', (select bool_and(public.ketvoice_profile_can_see(r, v, pub) = expected)
  from (values ('me', 'me', false, true), ('me', 'public', true, true), ('friend', 'me', true, false),
    ('friend', 'friends', false, true), ('friend', 'public', false, true), ('stranger', 'me', true, false),
    ('stranger', 'friends', true, false), ('stranger', 'public', false, false), ('stranger', 'public', true, true)) t(r, v, pub, expected)));

set local role anon;
select public.zz_as('');
select public.zz_fails('anon cannot ask for friends', $$select public.ketvoice_friend_request('bob')$$, '42501');
select public.zz_fails('anon cannot read presence', $$select count(*) from public.ketvoice_presence$$, '42501');
select public.zz_fails('anon cannot read a profile view', $$select public.ketvoice_profile_view('bob')$$, '42501');
select public.zz_ok('anon reads a private profile''s card as null, like an unknown handle',
  public.ketvoice_public_profile_card('alice') is null and public.ketvoice_public_profile_card('nobody_here') is null);

set local role authenticated;
-- Profile and modules ----------------------------------------------------------------------------------------------
select public.zz_as('alice');
update public.ketvoice_profiles set bio = 'Nuits blanches et synthés', accent = '#FF2D55' where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_fails('a bio longer than 160 characters is refused',
  $$update public.ketvoice_profiles set bio = repeat('a', 161) where id = 'aaaaaaaa-0000-4000-8000-000000000001'$$, '23514');
select public.zz_fails('an accent must be #RRGGBB', $$update public.ketvoice_profiles set accent = 'red' where id = 'aaaaaaaa-0000-4000-8000-000000000001'$$, '23514');
select public.zz_fails('a banner must be in the owner''s folder',
  $$update public.ketvoice_profiles set banner_url = 'bbbbbbbb-0000-4000-8000-000000000002/banner-1764000000000.jpg' where id = 'aaaaaaaa-0000-4000-8000-000000000001'$$, '23514');
update public.ketvoice_profiles set banner_url = 'aaaaaaaa-0000-4000-8000-000000000001/banner-1764000000001.jpg' where id = 'aaaaaaaa-0000-4000-8000-000000000001';
insert into public.ketvoice_profile_modules (user_id, module, data) values
  ('aaaaaaaa-0000-4000-8000-000000000001', 'top', '{"items":[{"name":"Néon"}]}'),
  ('aaaaaaaa-0000-4000-8000-000000000001', 'pinned', '{"lists":[{"name":"Secret"}]}'),
  ('aaaaaaaa-0000-4000-8000-000000000001', 'now', '{}');
select public.zz_ok('a new module is private', (select bool_and(reach = 'me') from public.ketvoice_profile_modules));
update public.ketvoice_profile_modules set reach = 'friends' where module = 'top';
update public.ketvoice_profile_modules set reach = 'public' where module = 'now';
select public.zz_fails('nobody writes a module for someone else',
  $$insert into public.ketvoice_profile_modules (user_id, module) values ('bbbbbbbb-0000-4000-8000-000000000002', 'top')$$, '42501');
select public.zz_fails('a module has a known name', $$insert into public.ketvoice_profile_modules (user_id, module) values ('aaaaaaaa-0000-4000-8000-000000000001', 'email')$$, '23514');
select public.zz_fails('a banner object outside one''s folder is refused',
  $$insert into storage.objects (bucket_id, name) values ('ketvoice-avatars', 'bbbbbbbb-0000-4000-8000-000000000002/banner-1764000000000.jpg')$$, '42501');
insert into storage.objects (bucket_id, name) values ('ketvoice-avatars', 'aaaaaaaa-0000-4000-8000-000000000001/banner-1764000000001.jpg');
insert into public.ketvoice_library (user_id, kind, doc, device_id) values ('aaaaaaaa-0000-4000-8000-000000000001', 'now_playing',
  '{"playing":true,"index":0,"queue":[{"id":"yt:abcdefghijk","title":"Ocean Drive","artist":"Néon","coverUrl":"https://i.ytimg.com/a.jpg"}]}', 'device-0001');
select public.zz_ok('the owner sees every module', (select public.ketvoice_profile_view('alice') -> 'modules' ?& array['top', 'pinned', 'now']));

select public.zz_as('carol');
select public.zz_ok('a stranger sees a private profile as private, with nothing of it',
  (select v ->> 'private' = 'true' and not v ? 'display_name' and not v ? 'modules' and public.zz_clean(v) from public.ketvoice_profile_view('alice') v));
select public.zz_fails('modules are not readable directly', $$select 1/count(*) from public.ketvoice_profile_modules$$, '22012');

-- Friendships ----------------------------------------------------------------------------------------------------
select public.zz_as('dave');
select public.zz_ok('asking needs a handle of one''s own', public.ketvoice_friend_request('bob') ->> 'status' = 'needs_handle');
select public.zz_as('alice');
select public.zz_ok('an unknown handle is unavailable', public.ketvoice_friend_request('nobody_here') ->> 'status' = 'unavailable');
select public.zz_ok('nobody asks themselves', public.ketvoice_friend_request('alice') ->> 'status' = 'self');
insert into zz values ('ab', public.zz_ref(public.ketvoice_friend_request('@Bob ')));
select public.zz_ok('asking twice says it is pending', public.ketvoice_friend_request('bob') ->> 'status' = 'pending');
select public.zz_ok('the asker sees the row, without the ids', (select count(*) = 1 from public.ketvoice_friendships));
select public.zz_fails('the account ids are not readable', $$select requester from public.ketvoice_friendships$$, '42501');
select public.zz_fails('nobody inserts a friendship directly',
  $$insert into public.ketvoice_friendships (requester, addressee, status, answered_at) values ('aaaaaaaa-0000-4000-8000-000000000001', 'cccccccc-0000-4000-8000-000000000003', 'accepted', now())$$, '42501');
select public.zz_ok('the asker cannot accept their own request', public.ketvoice_friend_answer((select v::uuid from zz where k = 'ab'), true) ->> 'status' = 'not_found');
select public.zz_as('carol');
select public.zz_ok('someone else sees no row and cannot answer', (select count(*) = 0 from public.ketvoice_friendships)
  and public.ketvoice_friend_answer((select v::uuid from zz where k = 'ab'), true) ->> 'status' = 'not_found');
select public.zz_as('bob');
select public.zz_ok('the person asked sees the request, by handle only',
  (select l -> 'incoming' -> 0 ->> 'handle' = 'alice' and public.zz_clean(l) from public.ketvoice_friend_lists() l));
select public.zz_ok('accepting makes them friends', public.ketvoice_friend_answer((select v::uuid from zz where k = 'ab'), true) ->> 'status' = 'accepted');
select public.zz_ok('then asking again says they are friends', public.ketvoice_friend_request('alice') ->> 'status' = 'friends');
select public.zz_ok('a friend sees the identity and the modules for friends and the public, not the private one',
  (select v ->> 'relation' = 'friend' and v ->> 'display_name' = 'Alice' and v -> 'modules' ? 'top' and v -> 'modules' ? 'now'
     and not v -> 'modules' ? 'pinned' and v -> 'listening' ->> 'title' = 'Ocean Drive' and public.zz_clean(v)
   from public.ketvoice_profile_view('alice') v));

select public.zz_as('carol');
insert into zz values ('ca', public.zz_ref(public.ketvoice_friend_request('alice')));
select public.zz_as('alice');
select public.zz_ok('asking someone who asked first accepts', public.ketvoice_friend_request('carol') ->> 'status' = 'accepted');
select public.zz_ok('either side ends a friendship', public.ketvoice_friend_remove((select v::uuid from zz where k = 'ca')) ->> 'status' = 'removed');
select public.zz_as('carol');
select public.zz_ok('then it is gone for both', (select jsonb_array_length(l -> 'friends') = 0 from public.ketvoice_friend_lists() l));
insert into zz values ('cb', public.zz_ref(public.ketvoice_friend_request('bob')));
select public.zz_as('bob');
select public.zz_ok('declining removes the request', public.ketvoice_friend_answer((select v::uuid from zz where k = 'cb'), false) ->> 'status' = 'declined');
select public.zz_ok('a declined request is gone', (select jsonb_array_length(l -> 'incoming') = 0 from public.ketvoice_friend_lists() l));

-- The public card and pictures ----------------------------------------------------------------------------------------
select public.zz_as('alice');
update public.ketvoice_profiles set is_public = true where id = 'aaaaaaaa-0000-4000-8000-000000000001';
set local role anon;
select public.zz_as('');
select public.zz_ok('anyone reads a public profile''s card: public modules only',
  (select c ->> 'display_name' = 'Alice' and c ->> 'bio' = 'Nuits blanches et synthés' and c -> 'modules' ? 'now'
     and not c -> 'modules' ? 'top' and not c -> 'modules' ? 'pinned' and c -> 'listening' ->> 'title' = 'Ocean Drive'
     and public.zz_clean(c) from public.ketvoice_public_profile_card('ALICE') c));
select public.zz_fails('anon cannot ask for a picture path', $$select public.ketvoice_profile_image(null, 'alice', 'avatar')$$, '42501');
set local role authenticated;
select public.zz_as('carol');
select public.zz_ok('a stranger sees a public profile''s public modules only',
  (select v ->> 'relation' = 'stranger' and v -> 'modules' ? 'now' and not v -> 'modules' ? 'top' from public.ketvoice_profile_view('alice') v));
select public.zz_fails('listeners cannot ask for a picture path', $$select public.ketvoice_profile_image(null, 'alice', 'avatar')$$, '42501');
reset role;
select public.zz_ok('the edge function gets a public profile''s photo for anyone, a private one''s only for friends',
  public.ketvoice_profile_image(null, 'alice', 'avatar') like 'aaaaaaaa-%/avatar-%'
  and public.ketvoice_profile_image(null, 'carol', 'avatar') is null
  and public.ketvoice_profile_image('cccccccc-0000-4000-8000-000000000003', 'alice', 'banner') like '%/banner-%'
  and public.ketvoice_profile_image(null, 'alice', 'email') is null);
update public.ketvoice_profiles set is_public = false where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_ok('a private profile''s picture goes to its friends and its owner only',
  public.ketvoice_profile_image('bbbbbbbb-0000-4000-8000-000000000002', 'alice', 'avatar') is not null
  and public.ketvoice_profile_image('aaaaaaaa-0000-4000-8000-000000000001', 'alice', 'avatar') is not null
  and public.ketvoice_profile_image('cccccccc-0000-4000-8000-000000000003', 'alice', 'avatar') is null
  and public.ketvoice_profile_image(null, 'alice', 'avatar') is null);

-- « Activité des amis » --------------------------------------------------------------------------------------------
set local role authenticated;
select public.zz_as('alice');
select public.zz_fails('nothing is shared before « Partager mon écoute » is on',
  $$insert into public.ketvoice_presence (user_id, track, playing) values ('aaaaaaaa-0000-4000-8000-000000000001', '{"id":"yt:abcdefghijk","title":"Ocean Drive"}', true)$$, '42501');
update public.ketvoice_profiles set share_listening = true where id = 'aaaaaaaa-0000-4000-8000-000000000001';
insert into public.ketvoice_presence (user_id, playing) values ('aaaaaaaa-0000-4000-8000-000000000001', false);
select public.zz_ok('the app''s upsert writes the title and the room', public.ketvoice_presence_put(
  '{"id":"yt:abcdefghijk","title":"Ocean Drive","artist":"Néon","streamUrl":"https://x.invalid/s"}', true, 'ABC234-ABCDEFGH') ->> 'status' = 'ok');
select public.zz_fails('a room is code-fingerprint', $$update public.ketvoice_presence set room = 'nope'$$, '23514');
select public.zz_fails('nobody writes someone else''s presence',
  $$insert into public.ketvoice_presence (user_id, playing) values ('bbbbbbbb-0000-4000-8000-000000000002', true)$$, '42501');
select public.zz_as('bob');
select public.zz_ok('a friend sees what is playing and the room, cleaned',
  (select n -> 0 ->> 'handle' = 'alice' and n -> 0 ->> 'room' = 'ABC234-ABCDEFGH' and n -> 0 -> 'track' ->> 'title' = 'Ocean Drive'
     and not n -> 0 -> 'track' ? 'streamUrl' and public.zz_clean(n) from public.ketvoice_friends_now() n));
select public.zz_ok('the friend''s row is readable through row level security', (select count(*) = 1 from public.ketvoice_presence));
select public.zz_fails('the owner id of a presence is not readable', $$select user_id from public.ketvoice_presence$$, '42501');
select public.zz_as('carol');
select public.zz_ok('someone who is not a friend sees nothing',
  (select jsonb_array_length(public.ketvoice_friends_now()) = 0) and (select count(*) = 0 from public.ketvoice_presence));
select public.zz_as('alice');
update public.ketvoice_presence set track = '{"id":"local:file:C:/x.mp3","title":"Mine"}';
select public.zz_ok('a local file never leaves the computer', (select track is null from public.ketvoice_presence));
update public.ketvoice_profiles set share_listening = false where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_ok('turning sharing off removes what was shared', (select count(*) = 0 from public.ketvoice_presence));

-- A block ends the friendship ----------------------------------------------------------------------------------------
reset role;
insert into public.ketvoice_blocks (blocker, blocked) values ('bbbbbbbb-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000001');
select public.zz_ok('a block removes the friendship', not exists (select 1 from public.ketvoice_friendships f
  where 'aaaaaaaa-0000-4000-8000-000000000001' in (f.requester, f.addressee) and 'bbbbbbbb-0000-4000-8000-000000000002' in (f.requester, f.addressee)));
set local role authenticated;
select public.zz_as('alice');
select public.zz_ok('a blocked person cannot ask again, nor see the profile',
  public.ketvoice_friend_request('bob') ->> 'status' = 'unavailable' and public.ketvoice_profile_view('bob') is null);

-- Playlist links --------------------------------------------------------------------------------------------------------
insert into public.ketvoice_link_playlists (owner_id, local_id, name, tracks) values ('aaaaaaaa-0000-4000-8000-000000000001', 'pl-1', 'Été',
  '[{"id":"yt:abcdefghijk","title":"Ocean Drive","artist":"Néon","durationMs":200000},{"id":"local:file:x","title":"Mine"}]');
insert into zz select 'link', id::text from public.ketvoice_link_playlists;
select public.zz_as('carol');
select public.zz_ok('another listener cannot list someone''s links', (select count(*) = 0 from public.ketvoice_link_playlists));
set local role anon;
select public.zz_as('');
select public.zz_ok('whoever has the link reads the snapshot, without its owner or local files',
  (select p ->> 'name' = 'Été' and jsonb_array_length(p -> 'tracks') = 1 and public.zz_clean(p)
   from public.ketvoice_link_playlist((select v::uuid from zz where k = 'link')) p));
select public.zz_ok('an unknown link answers null', public.ketvoice_link_playlist(gen_random_uuid()) is null);
select public.zz_fails('anon cannot read the links table', $$select count(*) from public.ketvoice_link_playlists$$, '42501');

-- Nothing here touched the publisher --------------------------------------------------------------------------------
reset role;
select public.zz_fails('the owner address stays reserved',
  $$insert into auth.users (id, email) values ('eeeeeeee-0000-4000-8000-00000000000e', 'KonnektSupport@gmail.com')$$, '42501');
delete from auth.users where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_ok('a deleted account leaves no module, friendship, presence or link behind',
  not exists (select 1 from public.ketvoice_profile_modules where user_id = 'aaaaaaaa-0000-4000-8000-000000000001')
  and not exists (select 1 from public.ketvoice_friendships where 'aaaaaaaa-0000-4000-8000-000000000001' in (requester, addressee))
  and not exists (select 1 from public.ketvoice_presence where user_id = 'aaaaaaaa-0000-4000-8000-000000000001')
  and not exists (select 1 from public.ketvoice_link_playlists where owner_id = 'aaaaaaaa-0000-4000-8000-000000000001'));

select 'all social assertions passed' as result;
rollback;
