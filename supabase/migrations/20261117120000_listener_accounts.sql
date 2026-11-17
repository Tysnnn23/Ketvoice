-- Ketvoice listener accounts: a profile per account, the library synced across the listener's devices,
-- and a private bucket for profile photos. Every row belongs to one auth user and row level security
-- lets that user, and only that user, read and write it. Nothing here grants anything on the publishing
-- tables, their policies or the public media bucket; listener accounts gain no publishing right.

-- Profiles ---------------------------------------------------------------------------------------------
create table public.ketvoice_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '' check (char_length(display_name) <= 40 and display_name !~ '[[:cntrl:]]'),
  handle text check (handle ~ '^[a-z0-9_.]{3,24}$'),
  -- The photo's object path in the private `ketvoice-avatars` bucket, never a public link.
  avatar_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ketvoice_profiles_avatar_own check (
    avatar_url is null or avatar_url ~ ('^' || id::text || '/avatar-[0-9]{10,16}\.jpg$'))
);
create unique index ketvoice_profiles_handle_key on public.ketvoice_profiles (handle) where handle is not null;

-- Synced library: one JSON document per account and kind -------------------------------------------------
create table public.ketvoice_library (
  user_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('favorites', 'playlists', 'artists', 'settings', 'now_playing')),
  doc jsonb not null default '{}'::jsonb check (jsonb_typeof(doc) = 'object' and pg_column_size(doc) <= 4194304),
  -- Counted by the server: a device writes « revision = N » and loses the race when another wrote first.
  revision bigint not null default 1,
  device_id text not null check (device_id ~ '^[A-Za-z0-9-]{8,64}$'),
  updated_at timestamptz not null default now(),
  primary key (user_id, kind)
);

create function public.ketvoice_library_stamp() returns trigger
language plpgsql set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    new.revision := 1;
  else
    if new.user_id <> old.user_id or new.kind <> old.kind then
      raise exception 'A library row keeps its owner and kind' using errcode = '42501';
    end if;
    new.revision := old.revision + 1;
  end if;
  new.updated_at := now();
  return new;
end $$;
create trigger ketvoice_library_stamp before insert or update on public.ketvoice_library
  for each row execute function public.ketvoice_library_stamp();

create function public.ketvoice_profiles_stamp() returns trigger
language plpgsql set search_path = '' as $$
begin
  if tg_op = 'UPDATE' and new.id <> old.id then
    raise exception 'A profile keeps its owner' using errcode = '42501';
  end if;
  new.updated_at := now();
  if tg_op = 'UPDATE' then new.created_at := old.created_at; end if;
  return new;
end $$;
create trigger ketvoice_profiles_stamp before insert or update on public.ketvoice_profiles
  for each row execute function public.ketvoice_profiles_stamp();

alter table public.ketvoice_profiles enable row level security;
alter table public.ketvoice_library enable row level security;
revoke all on public.ketvoice_profiles, public.ketvoice_library from public, anon, authenticated;
grant select, insert, update, delete on public.ketvoice_profiles, public.ketvoice_library to authenticated;
grant all on public.ketvoice_profiles, public.ketvoice_library to service_role;

create policy ketvoice_profiles_own_select on public.ketvoice_profiles for select to authenticated
  using ((select auth.uid()) = id);
create policy ketvoice_profiles_own_insert on public.ketvoice_profiles for insert to authenticated
  with check ((select auth.uid()) = id);
create policy ketvoice_profiles_own_update on public.ketvoice_profiles for update to authenticated
  using ((select auth.uid()) = id) with check ((select auth.uid()) = id);
create policy ketvoice_profiles_own_delete on public.ketvoice_profiles for delete to authenticated
  using ((select auth.uid()) = id);

create policy ketvoice_library_own_select on public.ketvoice_library for select to authenticated
  using ((select auth.uid()) = user_id);
create policy ketvoice_library_own_insert on public.ketvoice_library for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy ketvoice_library_own_update on public.ketvoice_library for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy ketvoice_library_own_delete on public.ketvoice_library for delete to authenticated
  using ((select auth.uid()) = user_id);

-- A profile for every new account, with the name given at sign-up (cleaned, at most 40 characters).
create function public.ketvoice_new_listener() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.ketvoice_profiles (id, display_name)
  values (new.id, left(btrim(regexp_replace(coalesce(new.raw_user_meta_data ->> 'display_name', ''), '[[:cntrl:]]', '', 'g')), 40))
  on conflict (id) do nothing;
  return new;
end $$;
revoke all on function public.ketvoice_new_listener() from public, anon, authenticated;
create trigger ketvoice_new_listener after insert on auth.users
  for each row execute function public.ketvoice_new_listener();

-- Deleting one's own account (profile and library go with it, by cascade). The photos must be removed
-- first through the Storage API (direct deletes on storage.objects are refused by Supabase), and the
-- verified publishing account can never be deleted this way.
create function public.ketvoice_delete_account() returns void
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if exists (select 1 from auth.users where id = uid and lower(email) = 'konnektsupport@gmail.com') then
    raise exception 'This account cannot be deleted here' using errcode = '42501';
  end if;
  if exists (select 1 from storage.objects where bucket_id = 'ketvoice-avatars' and name like uid::text || '/%') then
    raise exception 'Remove the profile photos first' using errcode = '55000';
  end if;
  delete from auth.users where id = uid;
end $$;
revoke all on function public.ketvoice_delete_account() from public, anon;
grant execute on function public.ketvoice_delete_account() to authenticated;

-- Profile photos: a private bucket, JPEG/WebP/PNG up to 1 MB, each account in its own folder ----------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('ketvoice-avatars', 'ketvoice-avatars', false, 1048576, array['image/jpeg', 'image/webp', 'image/png'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create policy ketvoice_avatars_own_select on storage.objects for select to authenticated
  using (bucket_id = 'ketvoice-avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy ketvoice_avatars_own_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'ketvoice-avatars'
    and name ~ ('^' || (select auth.uid())::text || '/avatar-[0-9]{10,16}\.jpg$'));
create policy ketvoice_avatars_own_update on storage.objects for update to authenticated
  using (bucket_id = 'ketvoice-avatars' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'ketvoice-avatars'
    and name ~ ('^' || (select auth.uid())::text || '/avatar-[0-9]{10,16}\.jpg$'));
create policy ketvoice_avatars_own_delete on storage.objects for delete to authenticated
  using (bucket_id = 'ketvoice-avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
