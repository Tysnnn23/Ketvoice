-- Owner-managed source priority (Studio drag order).
alter table public.ketvoice_managed_sources add column if not exists sort_order int not null default 0;
create index if not exists ketvoice_managed_sources_order_idx on public.ketvoice_managed_sources(created_by, sort_order, created_at);
comment on column public.ketvoice_managed_sources.sort_order is 'Display and resolution priority for the owner-managed sources.';
