-- Windows 2.0 social, part 2 of 9: friendships (a request, then a mutual friendship). Re-runnable.
-- Symmetric: a row is one pair, seen the same way by both people, whoever asked. Clients read their own rows
-- (row level security on both sides of the pair) but never the account ids in them (column grants); every
-- write goes through the functions of parts 3 and 4, which answer with handles and opaque refs only.
-- A block (ketvoice_blocks, 20261119120000) removes the pair's friendship and any pending request.

create table if not exists public.ketvoice_friendships (
  id uuid primary key default gen_random_uuid(),
  requester uuid not null references auth.users(id) on delete cascade,
  addressee uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted')),
  created_at timestamptz not null default now(),
  answered_at timestamptz,
  check (requester <> addressee),
  check ((status = 'accepted') = (answered_at is not null))
);
-- One row per pair, whichever way it was asked.
create unique index if not exists ketvoice_friendships_pair
  on public.ketvoice_friendships (least(requester, addressee), greatest(requester, addressee));
create index if not exists ketvoice_friendships_addressee on public.ketvoice_friendships (addressee, status);
create index if not exists ketvoice_friendships_requester on public.ketvoice_friendships (requester, status);

alter table public.ketvoice_friendships enable row level security;
revoke all on public.ketvoice_friendships from public, anon, authenticated;
grant select (id, status, created_at, answered_at) on public.ketvoice_friendships to authenticated;
grant all on public.ketvoice_friendships to service_role;

drop policy if exists ketvoice_friendships_pair_select on public.ketvoice_friendships;
create policy ketvoice_friendships_pair_select on public.ketvoice_friendships for select to authenticated
  using ((select auth.uid()) = requester or (select auth.uid()) = addressee);

-- Whether the caller and p_other are friends: an accepted row and no block either way. Answers only about
-- the caller (auth.uid()), so it cannot be used to learn who else is friends with whom.
create or replace function public.ketvoice_is_friend(p_other uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_other is not null and (select auth.uid()) is not null and exists (
    select 1 from public.ketvoice_friendships f
    where f.status = 'accepted'
      and least(f.requester, f.addressee) = least((select auth.uid()), p_other)
      and greatest(f.requester, f.addressee) = greatest((select auth.uid()), p_other))
    and not public.ketvoice_blocked_between((select auth.uid()), p_other)
$$;
revoke all on function public.ketvoice_is_friend(uuid) from public, anon;
grant execute on function public.ketvoice_is_friend(uuid) to authenticated;

-- The same rule between two given accounts, for the edge function (service role) and the functions below.
create or replace function public.ketvoice_friends_between(p_a uuid, p_b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_a is not null and p_b is not null and exists (
    select 1 from public.ketvoice_friendships f
    where f.status = 'accepted' and least(f.requester, f.addressee) = least(p_a, p_b)
      and greatest(f.requester, f.addressee) = greatest(p_a, p_b))
    and not public.ketvoice_blocked_between(p_a, p_b)
$$;
revoke all on function public.ketvoice_friends_between(uuid, uuid) from public, anon, authenticated;
grant execute on function public.ketvoice_friends_between(uuid, uuid) to service_role;

-- A block ends the friendship and any request between the two accounts.
create or replace function public.ketvoice_blocks_unfriend() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  delete from public.ketvoice_friendships f
  where least(f.requester, f.addressee) = least(new.blocker, new.blocked)
    and greatest(f.requester, f.addressee) = greatest(new.blocker, new.blocked);
  return null;
end $$;
revoke all on function public.ketvoice_blocks_unfriend() from public, anon, authenticated;
drop trigger if exists ketvoice_blocks_unfriend on public.ketvoice_blocks;
create trigger ketvoice_blocks_unfriend after insert on public.ketvoice_blocks
  for each row execute function public.ketvoice_blocks_unfriend();
