-- Windows 2.0 social, part 1 of 9: a customisable profile (bio, accent colour, banner) and its modules.
-- Re-runnable (IF NOT EXISTS, CREATE OR REPLACE, policies dropped first). Private by default: every new
-- module is visible to its owner only ('me') until the owner widens it. Touches no publishing table, no
-- catalogue table, no public media bucket; a listener account gains no publishing right.

alter table public.ketvoice_profiles add column if not exists bio text not null default '';
alter table public.ketvoice_profiles add column if not exists accent text;
-- The banner's object path in the private `ketvoice-avatars` bucket (never a public link), like avatar_url.
alter table public.ketvoice_profiles add column if not exists banner_url text;
-- « Partager mon écoute »: friends see what is playing now. Off until the listener turns it on.
alter table public.ketvoice_profiles add column if not exists share_listening boolean not null default false;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'ketvoice_profiles_bio_short'
                 and conrelid = 'public.ketvoice_profiles'::regclass) then
    alter table public.ketvoice_profiles add constraint ketvoice_profiles_bio_short
      check (char_length(bio) <= 160 and bio !~ '[[:cntrl:]]');
  end if;
  if not exists (select 1 from pg_constraint where conname = 'ketvoice_profiles_accent_hex'
                 and conrelid = 'public.ketvoice_profiles'::regclass) then
    alter table public.ketvoice_profiles add constraint ketvoice_profiles_accent_hex
      check (accent is null or accent ~ '^#[0-9A-F]{6}$');
  end if;
  if not exists (select 1 from pg_constraint where conname = 'ketvoice_profiles_banner_own'
                 and conrelid = 'public.ketvoice_profiles'::regclass) then
    alter table public.ketvoice_profiles add constraint ketvoice_profiles_banner_own
      check (banner_url is null or banner_url ~ ('^' || id::text || '/banner-[0-9]{10,16}\.jpg$'));
  end if;
end $$;

-- One row per module the owner set up: `top` (artists of the month, from the app's listening stats),
-- `pinned` (playlists pinned on the profile, a snapshot) and `now` (the title playing, read live from the
-- now_playing document; its row only holds the visibility). `reach`: me, friends or public.
create table if not exists public.ketvoice_profile_modules (
  user_id uuid not null references auth.users(id) on delete cascade,
  module text not null check (module in ('top', 'pinned', 'now')),
  reach text not null default 'me' check (reach in ('me', 'friends', 'public')),
  data jsonb not null default '{}'::jsonb check (jsonb_typeof(data) = 'object' and pg_column_size(data) <= 65536),
  updated_at timestamptz not null default now(),
  primary key (user_id, module)
);

create or replace function public.ketvoice_profile_modules_stamp() returns trigger
language plpgsql set search_path = '' as $$
begin
  if tg_op = 'UPDATE' and (new.user_id <> old.user_id or new.module <> old.module) then
    raise exception 'A module keeps its owner' using errcode = '42501';
  end if;
  new.updated_at := now();
  return new;
end $$;
revoke all on function public.ketvoice_profile_modules_stamp() from public, anon, authenticated;
drop trigger if exists ketvoice_profile_modules_stamp on public.ketvoice_profile_modules;
create trigger ketvoice_profile_modules_stamp before insert or update on public.ketvoice_profile_modules
  for each row execute function public.ketvoice_profile_modules_stamp();

alter table public.ketvoice_profile_modules enable row level security;
revoke all on public.ketvoice_profile_modules from public, anon, authenticated;
grant select, insert, update, delete on public.ketvoice_profile_modules to authenticated;
grant all on public.ketvoice_profile_modules to service_role;

drop policy if exists ketvoice_profile_modules_own_select on public.ketvoice_profile_modules;
create policy ketvoice_profile_modules_own_select on public.ketvoice_profile_modules for select to authenticated
  using ((select auth.uid()) = user_id);
drop policy if exists ketvoice_profile_modules_own_insert on public.ketvoice_profile_modules;
create policy ketvoice_profile_modules_own_insert on public.ketvoice_profile_modules for insert to authenticated
  with check ((select auth.uid()) = user_id);
drop policy if exists ketvoice_profile_modules_own_update on public.ketvoice_profile_modules;
create policy ketvoice_profile_modules_own_update on public.ketvoice_profile_modules for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists ketvoice_profile_modules_own_delete on public.ketvoice_profile_modules;
create policy ketvoice_profile_modules_own_delete on public.ketvoice_profile_modules for delete to authenticated
  using ((select auth.uid()) = user_id);

-- The banner beside the photo, in the owner's own folder of the private bucket (1 MB, JPEG). The existing
-- select and delete policies of that bucket already cover the whole folder.
drop policy if exists ketvoice_banners_own_insert on storage.objects;
create policy ketvoice_banners_own_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'ketvoice-avatars'
    and name ~ ('^' || (select auth.uid())::text || '/banner-[0-9]{10,16}\.jpg$'));
drop policy if exists ketvoice_banners_own_update on storage.objects;
create policy ketvoice_banners_own_update on storage.objects for update to authenticated
  using (bucket_id = 'ketvoice-avatars' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'ketvoice-avatars'
    and name ~ ('^' || (select auth.uid())::text || '/banner-[0-9]{10,16}\.jpg$'));
