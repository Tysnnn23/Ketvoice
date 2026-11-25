import { createProfileHandler, MAX_BYTES } from "./handler.ts";

// Deploy with verify_jwt=false (supabase/config.toml): link previews and <img> fetch a public picture without any
// header; the handler itself treats a request without a user's token as anonymous (public profiles only).
// The service role key never leaves this function. Nothing is logged.
const env = (name: string) => (Deno.env.get(name) ?? "").trim();
const base = () => env("SUPABASE_URL");
const service = () => ({ apikey: env("SUPABASE_SERVICE_ROLE_KEY"), Authorization: `Bearer ${env("SUPABASE_SERVICE_ROLE_KEY")}` });

Deno.serve(createProfileHandler({
  anonKey: () => env("SUPABASE_ANON_KEY"),
  async userOf(token) {
    const res = await fetch(`${base()}/auth/v1/user`, { redirect: "error", signal: AbortSignal.timeout(3_000),
      headers: { apikey: env("SUPABASE_ANON_KEY"), Authorization: `Bearer ${token}` } });
    if (!res.ok) return null;
    const user = await res.json();
    return typeof user?.id === "string" && !user.is_anonymous ? user.id : null;
  },
  async imagePath(viewer, handle, kind) {
    const res = await fetch(`${base()}/rest/v1/rpc/ketvoice_profile_image`, { method: "POST", redirect: "error",
      signal: AbortSignal.timeout(3_000), headers: { ...service(), "Content-Type": "application/json" },
      body: JSON.stringify({ p_viewer: viewer, p_handle: handle, p_kind: kind }) });
    if (!res.ok) return null;
    const path = await res.json();
    return typeof path === "string" ? path : null;
  },
  async download(path) {
    const res = await fetch(`${base()}/storage/v1/object/ketvoice-avatars/${path}`, { redirect: "error",
      signal: AbortSignal.timeout(5_000), headers: service() });
    if (!res.ok || Number(res.headers.get("content-length") ?? 0) > MAX_BYTES) return null;
    return new Uint8Array(await res.arrayBuffer());
  },
  // The project's rolling-window budget (20261026120000_api_rate_limit.sql): 240 pictures a minute per address.
  async quota(bucket) {
    const res = await fetch(`${base()}/rest/v1/rpc/ketvoice_api_take`, { method: "POST", redirect: "error",
      signal: AbortSignal.timeout(2_000), headers: { ...service(), "Content-Type": "application/json" },
      body: JSON.stringify({ p_ip: bucket, p_route: "profile-image", p_limit: 240, p_window_s: 60 }) });
    // A budget outage must not hide every profile picture: only an explicit refusal refuses.
    return !res.ok || await res.json() !== false;
  },
}));
