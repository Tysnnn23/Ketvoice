-- Service-only tables: no client role has any grant on them; row level security makes that explicit.
alter table public.ketvoice_api_hits enable row level security;
alter table public.ketvoice_search_cache enable row level security;
alter table public.ketvoice_active_listeners enable row level security;
