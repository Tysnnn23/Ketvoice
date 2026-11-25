// Shared pieces of the Windows 2.0 "Connexions" functions (device-link, devices, lastfm, my-data): JSON answers, the
// caller's verified account, the service role's REST calls and the rate limit. No secret is logged or returned.

export const ADMIN_EMAIL = "konnektsupport@gmail.com";

export interface ConnectEnv {
  /** The project URL (SUPABASE_URL). */
  url: string;
  /** The public anon key (SUPABASE_ANON_KEY): user-scoped REST calls. */
  anonKey: string;
  /** The service role key (SUPABASE_SERVICE_ROLE_KEY): never leaves the function. */
  serviceKey: string;
}

export interface ConnectDeps {
  env: ConnectEnv;
  fetcher: typeof fetch;
  now?: () => number;
}

export interface Caller {
  id: string;
  email: string;
  token: string;
  sessionId: string | null;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...cors },
  });
}

export const cors: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

export function preflight(req: Request): Response | null {
  return req.method === "OPTIONS" ? new Response(null, { status: 204, headers: cors }) : null;
}

export function isAdmin(email: string | null | undefined): boolean {
  return (email ?? "").trim().toLowerCase() === ADMIN_EMAIL;
}

export function bearer(req: Request): string | null {
  const value = req.headers.get("authorization") ?? "";
  const match = /^Bearer\s+([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/.exec(value.trim());
  return match ? match[1] : null;
}

/** The claims of a JWT whose signature was already checked (by the gateway and /auth/v1/user). */
export function jwtClaims(token: string): Record<string, unknown> | null {
  try {
    const part = token.split(".")[1];
    const padded = part.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((part.length + 3) % 4);
    return JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))));
  } catch {
    return null;
  }
}

/** The signed-in caller, as Supabase Auth itself says (never trusted from the request body). */
export async function caller(req: Request, deps: ConnectDeps): Promise<Caller | null> {
  const token = bearer(req);
  if (!token) return null;
  try {
    const r = await deps.fetcher(`${deps.env.url}/auth/v1/user`, {
      headers: { apikey: deps.env.anonKey, Authorization: `Bearer ${token}` },
      redirect: "error",
      signal: AbortSignal.timeout(5_000),
    });
    if (!r.ok) return null;
    const user = await r.json();
    if (typeof user?.id !== "string" || !UUID.test(user.id)) return null;
    const sid = jwtClaims(token)?.session_id;
    return { id: user.id, email: typeof user.email === "string" ? user.email : "", token, sessionId: typeof sid === "string" && UUID.test(sid) ? sid : null };
  } catch {
    return null;
  }
}

/** A PostgREST or Auth admin call with the service role. */
export async function service(deps: ConnectDeps, method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return await deps.fetcher(`${deps.env.url}${path}`, {
    method,
    headers: { apikey: deps.env.serviceKey, Authorization: `Bearer ${deps.env.serviceKey}`, "Content-Type": "application/json", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "error",
    signal: AbortSignal.timeout(8_000),
  });
}

/** A PostgREST call as the caller (row level security applies). */
export async function asUser(deps: ConnectDeps, token: string, path: string): Promise<Response> {
  return await deps.fetcher(`${deps.env.url}${path}`, {
    headers: { apikey: deps.env.anonKey, Authorization: `Bearer ${token}`, Accept: "application/json" },
    redirect: "error",
    signal: AbortSignal.timeout(8_000),
  });
}

/** The shared rolling-window limit (ketvoice_api_take); a failing backend refuses (fails closed). */
export async function take(deps: ConnectDeps, bucket: string, route: string, limit: number, windowS: number): Promise<boolean> {
  try {
    const r = await service(deps, "POST", "/rest/v1/rpc/ketvoice_api_take", { p_ip: bucket.slice(0, 120), p_route: route, p_limit: limit, p_window_s: windowS });
    return r.ok && (await r.json()) === true;
  } catch {
    return false;
  }
}

export function clientIp(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  return forwarded || req.headers.get("cf-connecting-ip") || "unknown";
}

/** The function's own route: the path after `/<function name>`. */
export function route(req: Request, name: string): string {
  const path = new URL(req.url).pathname;
  const at = path.indexOf(`/${name}`);
  return at >= 0 ? path.slice(at + name.length + 1) || "/" : path;
}

export async function body(req: Request, max = 64 * 1024): Promise<Record<string, unknown> | null> {
  try {
    const text = await req.text();
    if (text.length > max) return null;
    const value = text ? JSON.parse(text) : {};
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

export function cleanName(value: unknown, fallback = "Appareil"): string {
  const text = typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 40) : "";
  return text || fallback;
}
