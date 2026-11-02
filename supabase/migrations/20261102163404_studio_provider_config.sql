-- Public adapters only; writes are mediated by the verified-owner Edge handler.
create table public.ketvoice_provider_config (
  id smallint primary key default 1 check (id = 1),
  revision uuid not null default gen_random_uuid(),
  rules jsonb not null default '[]'::jsonb check (jsonb_typeof(rules) = 'array'),
  updated_by uuid references auth.users(id),
  updated_at timestamptz not null default now()
);
alter table public.ketvoice_provider_config enable row level security;
revoke all on public.ketvoice_provider_config from public, anon, authenticated;
grant select, update on public.ketvoice_provider_config to service_role;
insert into public.ketvoice_provider_config(id) values (1);
comment on table public.ketvoice_provider_config is 'Non-secret public JSON adapter overrides; service-only access via ketvoice-content.';
