import { createArtistHandler } from "./handler.ts";

// The provider key exists only in the hosted environment. No provider URLs,
// response bodies, exception messages or credentials are logged here.
const env = (name: string) => (Deno.env.get(name) ?? "").trim();

async function quota(bucket: string, limit: number, windowS: number): Promise<boolean> {
  const base = env("SUPABASE_URL");
  const role = env("SUPABASE_SERVICE_ROLE_KEY");
  if (!base || !role) return false;
  try {
    const response = await fetch(`${base}/rest/v1/rpc/ketvoice_api_take`, {
      method: "POST", redirect: "error", signal: AbortSignal.timeout(3_000),
      headers: { apikey: role, Authorization: `Bearer ${role}`, "Content-Type": "application/json" },
      body: JSON.stringify({ p_ip: "artist-broker", p_route: bucket, p_limit: limit, p_window_s: windowS }),
    });
    return response.ok && await response.json() === true;
  } catch { return false; } // A quota backend outage must not spend provider quota.
}

// Deploy with verify_jwt=true. The public project key is not the Fanart key.
Deno.serve(createArtistHandler({
  providerKey: () => env("FANART_API_KEY"),
  quota,
}));
