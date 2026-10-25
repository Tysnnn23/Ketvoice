create table public.ketvoice_managed_sources (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(name) between 1 and 100),
  manifest_url text not null check (length(manifest_url) between 12 and 2048 and manifest_url like 'https://%'),
  enabled boolean not null default true,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.ketvoice_managed_sources enable row level security;
revoke all on public.ketvoice_managed_sources from anon, authenticated;
create index ketvoice_managed_sources_active_idx on public.ketvoice_managed_sources (enabled) where enabled;
