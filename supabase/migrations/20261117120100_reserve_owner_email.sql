-- With e-mail sign-up open and « Confirm email » off, a new account is confirmed the moment it is made.
-- The verified publishing account is recognised by its confirmed e-mail, so that address is reserved:
-- no new account may take it and no account may change its address to it. The existing owner account
-- is untouched (its own updates never change its address). This only narrows what accounts can do;
-- the publishing tables, policies and functions are not modified.
create function public.ketvoice_reserve_owner_email() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if lower(coalesce(new.email, '')) = 'konnektsupport@gmail.com'
     and (tg_op = 'INSERT' or lower(coalesce(old.email, '')) <> 'konnektsupport@gmail.com') then
    raise exception 'This address is reserved' using errcode = '42501';
  end if;
  if lower(coalesce(new.email_change, '')) = 'konnektsupport@gmail.com'
     and lower(coalesce(new.email, '')) <> 'konnektsupport@gmail.com' then
    raise exception 'This address is reserved' using errcode = '42501';
  end if;
  return new;
end $$;
revoke all on function public.ketvoice_reserve_owner_email() from public, anon, authenticated;
create trigger ketvoice_reserve_owner_email before insert or update of email, email_change on auth.users
  for each row execute function public.ketvoice_reserve_owner_email();
