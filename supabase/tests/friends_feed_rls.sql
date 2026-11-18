-- Assertions for 20261119120000_friends_feed.sql (follows, blocks, activity, reactions, the feed).
--
-- Run on a database where the listener-account migrations (20261117120000, 20261117120100, 20261118120000)
-- and 20261119120000_friends_feed.sql are applied; locally, on a throwaway PostgreSQL 16, after
-- supabase/tests/supabase_stubs.sql:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/friends_feed_rls.sql
-- It runs in ONE transaction that is rolled back at the end, so it leaves nothing behind: it creates three
-- throw-away accounts (alice, bob, carol), impersonates them with `set local role authenticated` and
-- `request.jwt.claims`, and stops with an exception naming the first assertion that fails. Every line that
-- prints « ok … » passed. Run it as the `postgres` role (it inserts into auth.users and switches roles).

begin;

create function public.zz_ok(label text, passed boolean) returns void language plpgsql as $$
begin
  if passed is not true then
    raise exception 'FAILED: %', label;
  end if;
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

-- Who the session is: one of the three accounts, or nobody (a signed-in role without a user).
create function public.zz_as(who text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', case who
    when 'alice' then '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}'
    when 'bob' then '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}'
    when 'carol' then '{"sub":"cccccccc-0000-4000-8000-000000000003","role":"authenticated"}'
    else '' end, true);
end $$;

-- Nothing an RPC answers to someone else may hold an id or an address of the three accounts.
create function public.zz_clean(answer jsonb) returns boolean language sql as $$
  select answer::text !~ '(aaaaaaaa|bbbbbbbb|cccccccc)-0000' and answer::text !~ 'rls-test' and answer::text !~ 'konnektsupport'
    and answer::text !~ '"(user_id|follower|followed|actor|owner_id|email)"'
$$;

create function public.zz_feed(lim integer default 50) returns jsonb language sql as $$
  select public.ketvoice_friends_feed(null, lim)
$$;

grant execute on function public.zz_ok(text, boolean), public.zz_fails(text, text, text), public.zz_as(text),
  public.zz_clean(jsonb), public.zz_feed(integer) to anon, authenticated;

create temp table zz (k text primary key, v text);
grant all on zz to anon, authenticated;

-- Accounts ------------------------------------------------------------------------------------------------------
insert into auth.users (id, email) values
  ('aaaaaaaa-0000-4000-8000-000000000001', 'alice@rls-test.invalid'),
  ('bbbbbbbb-0000-4000-8000-000000000002', 'bob@rls-test.invalid'),
  ('cccccccc-0000-4000-8000-000000000003', 'carol@rls-test.invalid');
update public.ketvoice_profiles set display_name = 'Alice', handle = 'alice', is_public = true, show_listening = true,
  avatar_ref = 'dddddddd-0000-4000-8000-00000000000d' where id = 'aaaaaaaa-0000-4000-8000-000000000001';
update public.ketvoice_profiles set display_name = 'Bob', handle = 'bob', is_public = true where id = 'bbbbbbbb-0000-4000-8000-000000000002';
update public.ketvoice_profiles set display_name = 'Carol' where id = 'cccccccc-0000-4000-8000-000000000003';

select public.zz_ok('« Publier mon activité » is on by default',
  (select bool_and(share_activity) from public.ketvoice_profiles));
select public.zz_fails('the owner address stays reserved',
  $$insert into auth.users (id, email) values ('eeeeeeee-0000-4000-8000-00000000000e', 'KonnektSupport@gmail.com')$$, '42501');

-- What is reachable at all ---------------------------------------------------------------------------------------
select public.zz_ok('row level security is on for every new table', (select bool_and(c.relrowsecurity) from pg_class c
  where c.oid in ('public.ketvoice_follows'::regclass, 'public.ketvoice_blocks'::regclass,
                  'public.ketvoice_activity'::regclass, 'public.ketvoice_reactions'::regclass)));
select public.zz_ok('neither anon nor authenticated holds any grant on the new tables', not exists (
  select 1 from information_schema.role_table_grants
  where grantee in ('anon', 'authenticated', 'PUBLIC') and table_schema = 'public'
    and table_name in ('ketvoice_follows', 'ketvoice_blocks', 'ketvoice_activity', 'ketvoice_reactions')));
select public.zz_ok('anon cannot execute any function of this feature', not exists (
  select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and (p.proname like 'ketvoice_follow%' or p.proname in ('ketvoice_unfollow', 'ketvoice_block',
    'ketvoice_react', 'ketvoice_friends_feed', 'ketvoice_public_profile', 'ketvoice_clean_handle', 'ketvoice_blocked_between',
    'ketvoice_activity_allowed', 'ketvoice_can_see_activity', 'ketvoice_activity_track', 'ketvoice_activity_add',
    'ketvoice_block_account', 'ketvoice_public_playlists_activity', 'ketvoice_listening_activity'))
    and has_function_privilege('anon', p.oid, 'execute')));
select public.zz_ok('the internal helpers and triggers are not callable by listeners', not exists (
  select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.proname in ('ketvoice_clean_handle', 'ketvoice_blocked_between', 'ketvoice_activity_allowed',
    'ketvoice_can_see_activity', 'ketvoice_activity_track', 'ketvoice_activity_add', 'ketvoice_block_account',
    'ketvoice_public_playlists_activity', 'ketvoice_listening_activity')
    and has_function_privilege('authenticated', p.oid, 'execute')));
select public.zz_ok('every function of this feature has an empty search_path, and the RPCs are security definer', not exists (
  select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and (p.proname like 'ketvoice_follow%' or p.proname in ('ketvoice_unfollow', 'ketvoice_block',
    'ketvoice_react', 'ketvoice_friends_feed', 'ketvoice_public_profile', 'ketvoice_block_account', 'ketvoice_activity_add',
    'ketvoice_can_see_activity', 'ketvoice_listening_activity', 'ketvoice_public_playlists_activity'))
    and not (p.prosecdef and p.proconfig @> array['search_path=""'])));

set local role anon;
select public.zz_as('');
select public.zz_fails('anon cannot read the feed', $$select public.ketvoice_friends_feed(null, 10)$$, '42501');
select public.zz_fails('anon cannot follow', $$select public.ketvoice_follow('alice')$$, '42501');
select public.zz_fails('anon cannot react', $$select public.ketvoice_react(gen_random_uuid(), 'heart')$$, '42501');
select public.zz_fails('anon cannot list follows', $$select public.ketvoice_follow_lists()$$, '42501');
select public.zz_fails('anon cannot read the follows table', $$select count(*) from public.ketvoice_follows$$, '42501');
select public.zz_fails('anon cannot read the activity table', $$select count(*) from public.ketvoice_activity$$, '42501');

set local role authenticated;
select public.zz_fails('a session without a user reads no feed', $$select public.ketvoice_friends_feed(null, 10)$$, '42501');

-- Following ------------------------------------------------------------------------------------------------------
select public.zz_as('carol');
select public.zz_ok('following needs a handle of one''s own', public.ketvoice_follow('alice') ->> 'status' = 'needs_handle');
update public.ketvoice_profiles set handle = 'carol' where id = 'cccccccc-0000-4000-8000-000000000003';
select public.zz_ok('a public profile can be followed without approval', public.ketvoice_follow('@Bob ') ->> 'status' = 'following');
select public.zz_ok('following twice says so', public.ketvoice_follow('bob') ->> 'status' = 'already');

select public.zz_as('bob');
select public.zz_ok('a private profile cannot be followed, with the same answer as an unknown handle',
  public.ketvoice_follow('carol') = public.ketvoice_follow('nobody_here') and public.ketvoice_follow('carol') ->> 'status' = 'unavailable');
select public.zz_ok('a malformed handle is unavailable', public.ketvoice_follow('x') ->> 'status' = 'unavailable');
select public.zz_ok('bob follows alice', public.ketvoice_follow('alice') ->> 'status' = 'following');
select public.zz_as('alice');
select public.zz_ok('nobody follows themselves', public.ketvoice_follow('alice') ->> 'status' = 'self');

select public.zz_fails('the follows table is closed to listeners', $$select count(*) from public.ketvoice_follows$$, '42501');
select public.zz_fails('nobody inserts a follow directly',
  $$insert into public.ketvoice_follows (follower, followed) values ('aaaaaaaa-0000-4000-8000-000000000001', 'bbbbbbbb-0000-4000-8000-000000000002')$$, '42501');
select public.zz_fails('nobody writes activity directly',
  $$insert into public.ketvoice_activity (actor, kind, track) values ('aaaaaaaa-0000-4000-8000-000000000001', 'listening', '{}')$$, '42501');
select public.zz_fails('nobody reads activity directly', $$select count(*) from public.ketvoice_activity$$, '42501');
select public.zz_fails('nobody reads reactions directly', $$select count(*) from public.ketvoice_reactions$$, '42501');
select public.zz_fails('nobody reads blocks directly', $$select count(*) from public.ketvoice_blocks$$, '42501');

-- Alice publishes ------------------------------------------------------------------------------------------------
insert into public.ketvoice_public_playlists (id, owner_id, local_id, name, cover_url, tracks)
values ('11111111-0000-4000-8000-0000000000a1', 'aaaaaaaa-0000-4000-8000-000000000001', 'pl-1', 'Road trip', null,
  '[{"id":"t1","title":"One","artist":"A","coverUrl":"https://example.com/1.jpg"}]');

select public.zz_as('bob');
select public.zz_ok('a follower sees « a publié la playlist »', (select count(*) = 1 and bool_and(e ->> 'kind' = 'published'
    and e ->> 'handle' = 'alice' and e ->> 'display_name' = 'Alice' and e -> 'playlist' ->> 'name' = 'Road trip'
    and e -> 'playlist' ->> 'cover_url' = 'https://example.com/1.jpg' and e ->> 'avatar_ref' = 'dddddddd-0000-4000-8000-00000000000d'
    and (e ->> 'titles')::int = 1)
  from jsonb_array_elements(public.zz_feed()) e));
select public.zz_ok('the feed holds no id and no address', public.zz_clean(public.zz_feed()));
select public.zz_ok('the profile says bob follows alice, and nothing else about bob',
  (public.ketvoice_public_profile('alice') ->> 'following')::boolean and not (public.ketvoice_public_profile('alice') ->> 'is_me')::boolean
  and public.zz_clean(public.ketvoice_public_profile('alice')));
select public.zz_as('carol');
select public.zz_ok('someone who does not follow alice sees nothing of her', jsonb_array_length(public.zz_feed()) = 0);

-- Titles added: just after publishing they belong to the publication, later they make one « a ajouté » line.
select public.zz_as('alice');
update public.ketvoice_public_playlists set tracks = tracks || '[{"id":"t2","title":"Two","artist":"B"}]'::jsonb
where id = '11111111-0000-4000-8000-0000000000a1';
select public.zz_as('bob');
select public.zz_ok('adds right after publishing make no extra line', jsonb_array_length(public.zz_feed()) = 1);
reset role;
update public.ketvoice_activity set created_at = now() - interval '20 minutes' where actor = 'aaaaaaaa-0000-4000-8000-000000000001';
set local role authenticated;
select public.zz_as('alice');
update public.ketvoice_public_playlists set tracks = tracks || '[{"id":"t3","title":"Three","artist":"C"},{"id":"t4","title":"Four","artist":"D"}]'::jsonb
where id = '11111111-0000-4000-8000-0000000000a1';
update public.ketvoice_public_playlists set tracks = tracks || '[{"id":"t5","title":"Five","artist":"E"}]'::jsonb
where id = '11111111-0000-4000-8000-0000000000a1';
update public.ketvoice_public_playlists set name = 'Road trip 2' where id = '11111111-0000-4000-8000-0000000000a1';
select public.zz_as('bob');
select public.zz_ok('later adds make one « a ajouté 3 titres » line, with the playlist''s current name',
  (select count(*) filter (where e ->> 'kind' = 'added') = 1 and bool_or(e ->> 'kind' = 'added' and (e ->> 'titles')::int = 3
    and e -> 'playlist' ->> 'name' = 'Road trip 2') from jsonb_array_elements(public.zz_feed()) e));
select public.zz_ok('newest first', public.zz_feed() -> 0 ->> 'kind' = 'added');

-- Listening ------------------------------------------------------------------------------------------------------
select public.zz_as('alice');
insert into public.ketvoice_library (user_id, kind, doc, device_id) values ('aaaaaaaa-0000-4000-8000-000000000001', 'now_playing',
  jsonb_build_object('playing', true, 'index', 1, 'queue', jsonb_build_array(
    jsonb_build_object('id', 'q0', 'title', 'Before'),
    jsonb_build_object('id', 'q1', 'title', repeat('L', 400), 'artist', 'Artist', 'coverUrl', 'https://example.com/q1.jpg',
      'streamUrl', 'https://stream.invalid/secret', 'downloadUrl', 'https://dl.invalid/x', 'videoId', 'abc', 'durationMs', 200000))),
  'device-0001');
update public.ketvoice_library set doc = jsonb_set(doc, '{index}', '0') where user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and kind = 'now_playing';
select public.zz_as('bob');
select public.zz_ok('« écoute » appears once in ten minutes, cut to size, without any stream address', (
  select count(*) = 1 and bool_and(e -> 'track' ->> 'id' = 'q1' and char_length(e -> 'track' ->> 'title') = 300
    and e -> 'track' ->> 'coverUrl' = 'https://example.com/q1.jpg' and e -> 'track' ->> 'videoId' = 'abc'
    and not (e -> 'track') ? 'streamUrl' and not (e -> 'track') ? 'downloadUrl' and e ->> 'playlist' is null)
  from jsonb_array_elements(public.zz_feed()) e where e ->> 'kind' = 'listening'));
reset role;
update public.ketvoice_activity set created_at = now() - interval '11 minutes' where kind = 'listening';
set local role authenticated;
select public.zz_as('alice');
update public.ketvoice_library set doc = jsonb_set(doc, '{index}', '1') where user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and kind = 'now_playing';
select public.zz_as('bob');
select public.zz_ok('the same title again is not repeated within three hours',
  (select count(*) from jsonb_array_elements(public.zz_feed()) e where e ->> 'kind' = 'listening') = 1);
select public.zz_as('alice');
update public.ketvoice_library set doc = jsonb_set(doc, '{index}', '0') where user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and kind = 'now_playing';
select public.zz_as('bob');
select public.zz_ok('another title after ten minutes makes a new line',
  (select count(*) from jsonb_array_elements(public.zz_feed()) e where e ->> 'kind' = 'listening') = 2);
reset role;
update public.ketvoice_activity set created_at = created_at - interval '11 minutes' where kind = 'listening';
set local role authenticated;
select public.zz_as('alice');
update public.ketvoice_library set doc = jsonb_build_object('playing', false, 'index', 0, 'queue', jsonb_build_array(
  jsonb_build_object('id', 'p0', 'title', 'Paused'))) where user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and kind = 'now_playing';
update public.ketvoice_library set doc = jsonb_build_object('playing', true, 'index', 0, 'queue', jsonb_build_array(
  jsonb_build_object('id', 'local:file-1', 'title', 'My own file'))) where user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and kind = 'now_playing';
update public.ketvoice_library set doc = jsonb_build_object('playing', true, 'index', 7, 'queue', '[]'::jsonb)
where user_id = 'aaaaaaaa-0000-4000-8000-000000000001' and kind = 'now_playing';
select public.zz_as('bob');
select public.zz_ok('a paused title, a file of the device or a broken document make no line',
  (select count(*) from jsonb_array_elements(public.zz_feed()) e where e ->> 'kind' = 'listening') = 2);
select public.zz_as('bob');
insert into public.ketvoice_library (user_id, kind, doc, device_id) values ('bbbbbbbb-0000-4000-8000-000000000002', 'now_playing',
  jsonb_build_object('playing', true, 'index', 0, 'queue', jsonb_build_array(jsonb_build_object('id', 'b1', 'title', 'Bob plays'))), 'device-0002');
select public.zz_as('carol');
select public.zz_ok('without « Montrer ce que j''écoute » nothing of what bob plays is written',
  jsonb_array_length(public.zz_feed()) = 0);

-- Switches that hide ---------------------------------------------------------------------------------------------
select public.zz_as('alice');
update public.ketvoice_profiles set show_listening = false where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_as('bob');
select public.zz_ok('turning « Montrer ce que j''écoute » off hides the listening lines at once',
  (select count(*) from jsonb_array_elements(public.zz_feed()) e where e ->> 'kind' = 'listening') = 0
  and jsonb_array_length(public.zz_feed()) = 2);
select public.zz_as('alice');
update public.ketvoice_profiles set show_listening = true, share_activity = false where id = 'aaaaaaaa-0000-4000-8000-000000000001';
insert into public.ketvoice_public_playlists (id, owner_id, local_id, name, tracks)
values ('11111111-0000-4000-8000-0000000000a2', 'aaaaaaaa-0000-4000-8000-000000000001', 'pl-2', 'Quiet', '[]');
select public.zz_as('bob');
select public.zz_ok('« Publier mon activité » off: nothing shown', jsonb_array_length(public.zz_feed()) = 0);
select public.zz_as('alice');
update public.ketvoice_profiles set share_activity = true where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_as('bob');
select public.zz_ok('… and nothing was written meanwhile (no line for the playlist published then)',
  jsonb_array_length(public.zz_feed()) = 4 and not exists (
    select 1 from jsonb_array_elements(public.zz_feed()) e where e -> 'playlist' ->> 'name' = 'Quiet'));
select public.zz_as('alice');
update public.ketvoice_profiles set is_public = false, show_listening = false where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_as('bob');
select public.zz_ok('a profile turned private shows nothing to its followers', jsonb_array_length(public.zz_feed()) = 0);
select public.zz_as('carol');
select public.zz_ok('and cannot be followed', public.ketvoice_follow('alice') ->> 'status' = 'unavailable');
select public.zz_as('alice');
update public.ketvoice_profiles set is_public = true, show_listening = true where id = 'aaaaaaaa-0000-4000-8000-000000000001';

-- Reactions ------------------------------------------------------------------------------------------------------
select public.zz_as('bob');
insert into zz select 'line', public.zz_feed() -> 0 ->> 'id';
insert into zz select 'listen', (select e ->> 'id' from jsonb_array_elements(public.zz_feed()) e where e ->> 'kind' = 'listening' limit 1);
select public.zz_ok('a follower reacts', (select public.ketvoice_react(v::uuid, 'heart') ->> 'status' = 'ok' from zz where k = 'line'));
select public.zz_ok('one reaction per person: a new one replaces it', (select r -> 'reactions' = '{"fire": 1}'::jsonb and r ->> 'mine' = 'fire'
  from (select public.ketvoice_react(v::uuid, 'fire') as r from zz where k = 'line') x));
select public.zz_ok('the feed counts the reactions and shows the caller''s own',
  (select e -> 'reactions' = '{"fire": 1}'::jsonb and e ->> 'mine' = 'fire' from jsonb_array_elements(public.zz_feed()) e
   where e ->> 'id' = (select v from zz where k = 'line')));
select public.zz_fails('only the fixed set of reactions',
  $$select public.ketvoice_react((select v::uuid from zz where k = 'line'), 'poop')$$, '22023');
select public.zz_as('carol');
select public.zz_ok('someone who does not follow cannot react', (select public.ketvoice_react(v::uuid, 'heart') ->> 'status' = 'unavailable' from zz where k = 'line'));
select public.zz_ok('nor guess a line', public.ketvoice_react(gen_random_uuid(), 'heart') ->> 'status' = 'unavailable');
select public.zz_as('alice');
select public.zz_ok('the author does not react to their own line', (select public.ketvoice_react(v::uuid, 'heart') ->> 'status' = 'unavailable' from zz where k = 'line'));
select public.zz_as('bob');
select public.zz_ok('a reaction can be taken back', (select r ->> 'status' = 'removed' and r -> 'reactions' = '{}'::jsonb
  from (select public.ketvoice_react(v::uuid, null) as r from zz where k = 'line') x));
select public.ketvoice_react(v::uuid, 'clap') from zz where k = 'listen';
select public.zz_as('alice');
update public.ketvoice_profiles set show_listening = false where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_as('bob');
select public.zz_ok('a hidden listening line takes no reaction', (select public.ketvoice_react(v::uuid, 'heart') ->> 'status' = 'unavailable' from zz where k = 'listen'));
select public.zz_as('alice');
update public.ketvoice_profiles set show_listening = true where id = 'aaaaaaaa-0000-4000-8000-000000000001';

-- Paging ---------------------------------------------------------------------------------------------------------
reset role;
insert into public.ketvoice_activity (actor, kind, track, created_at)
select 'aaaaaaaa-0000-4000-8000-000000000001', 'listening', jsonb_build_object('id', 'old' || g, 'title', 'Old ' || g),
  now() - make_interval(days => 1, mins => g) from generate_series(1, 7) g;
set local role authenticated;
select public.zz_as('bob');
select public.zz_ok('the feed holds the eleven lines', jsonb_array_length(public.zz_feed()) = 11);
insert into zz select 'p1', public.ketvoice_friends_feed(null, 4)::text;
insert into zz select 'p2', public.ketvoice_friends_feed((select ((v::jsonb) -> 3 ->> 'at')::timestamptz from zz where k = 'p1'), 4)::text;
insert into zz select 'p3', public.ketvoice_friends_feed((select ((v::jsonb) -> 3 ->> 'at')::timestamptz from zz where k = 'p2'), 4)::text;
select public.zz_ok('pages of 4, 4 and 3 follow each other without overlap, newest first', (
  select jsonb_array_length(a.v::jsonb) = 4 and jsonb_array_length(b.v::jsonb) = 4 and jsonb_array_length(c.v::jsonb) = 3
    and ((a.v::jsonb) -> 3 ->> 'at')::timestamptz > ((b.v::jsonb) -> 0 ->> 'at')::timestamptz
    and ((b.v::jsonb) -> 3 ->> 'at')::timestamptz > ((c.v::jsonb) -> 0 ->> 'at')::timestamptz
    and (select count(distinct e ->> 'id') from jsonb_array_elements(a.v::jsonb || b.v::jsonb || c.v::jsonb) e) = 11
  from zz a, zz b, zz c where a.k = 'p1' and b.k = 'p2' and c.k = 'p3'));
select public.zz_ok('a page is at most 50 lines and at least 1', jsonb_array_length(public.ketvoice_friends_feed(null, 0)) = 1
  and jsonb_array_length(public.ketvoice_friends_feed(null, 100000)) = 11);

-- Pruning: older than 30 days is never shown, and goes on the next write.
reset role;
update public.ketvoice_activity set created_at = now() - interval '31 days' where track ->> 'id' = 'old7';
set local role authenticated;
select public.zz_as('bob');
select public.zz_ok('a line older than 30 days is not shown', jsonb_array_length(public.zz_feed()) = 10);
select public.zz_as('alice');
insert into public.ketvoice_public_playlists (owner_id, local_id, name, tracks)
values ('aaaaaaaa-0000-4000-8000-000000000001', 'pl-3', 'Fresh', '[]');
reset role;
select public.zz_ok('… and it is pruned on the next write', not exists (select 1 from public.ketvoice_activity where track ->> 'id' = 'old7'));
set local role authenticated;

-- Unpublishing removes the lines of that playlist.
select public.zz_as('alice');
delete from public.ketvoice_public_playlists where local_id = 'pl-3';
select public.zz_as('bob');
select public.zz_ok('an unpublished playlist leaves the feed', not exists (
  select 1 from jsonb_array_elements(public.zz_feed()) e where e -> 'playlist' ->> 'name' = 'Fresh'));

-- Lists, removing a follower, blocking ------------------------------------------------------------------------------
select public.zz_as('bob');
select public.zz_ok('bob''s lists: alice in following, carol (private) in followers, without ids or a private photo', (
  select l -> 'following' -> 0 ->> 'handle' = 'alice' and l -> 'following' -> 0 ->> 'avatar_ref' = 'dddddddd-0000-4000-8000-00000000000d'
    and l -> 'followers' -> 0 ->> 'handle' = 'carol' and l -> 'followers' -> 0 ->> 'display_name' = 'Carol'
    and l -> 'followers' -> 0 -> 'avatar_ref' = 'null'::jsonb and not (l -> 'followers' -> 0 ->> 'is_public')::boolean
    and not (l -> 'followers' -> 0 ->> 'following')::boolean and jsonb_array_length(l -> 'blocked') = 0
    and public.zz_clean(l) from (select public.ketvoice_follow_lists() as l) x));
insert into zz select 'carol-ref', public.ketvoice_follow_lists() -> 'followers' -> 0 ->> 'ref';
select public.zz_as('carol');
select public.zz_ok('a ref is not the follower''s to remove from the other side',
  (select public.ketvoice_follow_manage(v::uuid, 'remove') ->> 'status' = 'not_found' from zz where k = 'carol-ref'));
select public.zz_as('bob');
select public.zz_ok('bob removes carol from his followers', (select public.ketvoice_follow_manage(v::uuid, 'remove') ->> 'status' = 'done' from zz where k = 'carol-ref'));
select public.zz_ok('she is gone from the list', jsonb_array_length(public.ketvoice_follow_lists() -> 'followers') = 0);
select public.zz_fails('an unknown action is refused', $$select public.ketvoice_follow_manage(gen_random_uuid(), 'steal')$$, '22023');
select public.zz_as('carol');
select public.zz_ok('removed is not blocked: carol may follow again', public.ketvoice_follow('bob') ->> 'status' = 'following');
select public.zz_as('alice');
select public.zz_ok('a private account that does not follow cannot be blocked by handle (nothing to probe)',
  public.ketvoice_block('carol') ->> 'status' = 'unavailable');

select public.zz_as('bob');
select public.ketvoice_react(v::uuid, 'heart') from zz where k = 'line';
select public.zz_as('alice');
select public.zz_ok('alice sees bob among her followers', public.ketvoice_follow_lists() -> 'followers' -> 0 ->> 'handle' = 'bob');
select public.zz_ok('alice blocks bob from her followers list', (select public.ketvoice_follow_manage(
  (public.ketvoice_follow_lists() -> 'followers' -> 0 ->> 'ref')::uuid, 'block') ->> 'status' = 'done'));
select public.zz_ok('bob is in her blocked list, not in her followers', (select jsonb_array_length(l -> 'followers') = 0
  and l -> 'blocked' -> 0 ->> 'handle' = 'bob' and public.zz_clean(l) from (select public.ketvoice_follow_lists() as l) x));
reset role;
select public.zz_ok('the block took bob''s reactions away', not exists (
  select 1 from public.ketvoice_reactions where user_id = 'bbbbbbbb-0000-4000-8000-000000000002'));
set local role authenticated;
select public.zz_as('bob');
select public.zz_ok('blocked: bob''s feed is empty', jsonb_array_length(public.zz_feed()) = 0);
select public.zz_ok('blocked: bob cannot follow alice again', public.ketvoice_follow('alice') ->> 'status' = 'unavailable');
select public.zz_ok('blocked: alice''s profile answers like a private one', public.ketvoice_public_profile('alice') is null);
select public.zz_ok('blocked: bob cannot react', (select public.ketvoice_react(v::uuid, 'heart') ->> 'status' = 'unavailable' from zz where k = 'line'));
select public.zz_ok('blocked: bob does not see alice in his following list', jsonb_array_length(public.ketvoice_follow_lists() -> 'following') = 0);
select public.zz_as('alice');
select public.zz_ok('alice cannot follow bob while she blocks him', public.ketvoice_follow('bob') ->> 'status' = 'unavailable');
select public.zz_ok('alice unblocks bob', (select public.ketvoice_follow_manage((public.ketvoice_follow_lists() -> 'blocked' -> 0 ->> 'ref')::uuid,
  'unblock') ->> 'status' = 'done'));
select public.zz_as('bob');
select public.zz_ok('unblocked: bob may follow again (the old follow is not restored)',
  public.ketvoice_public_profile('alice') ->> 'following' = 'false' and public.ketvoice_follow('alice') ->> 'status' = 'following');
select public.zz_ok('… and unfollow by handle', public.ketvoice_unfollow('alice') ->> 'status' = 'removed'
  and public.ketvoice_unfollow('alice') ->> 'status' = 'not_following');

-- The limit of 500 follows -----------------------------------------------------------------------------------------
reset role;
insert into auth.users (id, email) select ('00000000-0000-4000-9000-' || lpad(g::text, 12, '0'))::uuid, 'many' || g || '@rls-test.invalid'
from generate_series(1, 500) g;
update public.ketvoice_profiles set handle = 'many_' || substr(id::text, 25), is_public = true where id::text like '00000000-0000-4000-9000-%';
insert into public.ketvoice_follows (follower, followed) select 'cccccccc-0000-4000-8000-000000000003', id
from public.ketvoice_profiles where id::text like '00000000-0000-4000-9000-%' and handle <> 'many_000000000500';
set local role authenticated;
select public.zz_as('carol');
select public.zz_ok('carol already follows 500 accounts (bob and 499 others)', jsonb_array_length(public.ketvoice_follow_lists() -> 'following') = 500);
select public.zz_ok('the 501st follow is refused', public.ketvoice_follow('many_000000000500') ->> 'status' = 'limit');
select public.zz_ok('after unfollowing one, it works', public.ketvoice_unfollow('bob') ->> 'status' = 'removed'
  and public.ketvoice_follow('many_000000000500') ->> 'status' = 'following');

-- Deleting an account takes its follows, blocks, activity and reactions with it.
reset role;
delete from auth.users where id = 'aaaaaaaa-0000-4000-8000-000000000001';
select public.zz_ok('a deleted account leaves no follow, block, activity or reaction behind', not exists (
    select 1 from public.ketvoice_follows where 'aaaaaaaa-0000-4000-8000-000000000001' in (follower, followed))
  and not exists (select 1 from public.ketvoice_activity where actor = 'aaaaaaaa-0000-4000-8000-000000000001')
  and not exists (select 1 from public.ketvoice_blocks where 'aaaaaaaa-0000-4000-8000-000000000001' in (blocker, blocked))
  and not exists (select 1 from public.ketvoice_reactions where user_id = 'aaaaaaaa-0000-4000-8000-000000000001'));

select 'all friends-feed assertions passed' as result;
rollback;
