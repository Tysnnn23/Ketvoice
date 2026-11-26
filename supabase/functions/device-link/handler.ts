// Sign-in by QR code (device code flow). A PC without a session asks for a code (/start), shows it, and polls
// (/poll with its secret) until someone signed in on the website approves it (/info then /approve, or /deny). Codes
// live 5 minutes and work once; only a SHA-256 of the poll secret is stored; /start is rate limited per address and
// /approve per account. The approved account's one-time sign-in token (a magic link's hashed token, never e-mailed)
// is handed to the PC once, which exchanges it for its own session. The publishing account is never signed in this way.
import {
  caller, clientIp, cleanName, type ConnectDeps, body as readBody, isAdmin, json, preflight, route, service, sha256Hex, take,
} from "../_shared/connect.ts";

export const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const TTL_S = 300;
export const POLL_S = 3;
const CODE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/;
const SECRET = /^[A-Za-z0-9_-]{43}$/;
const KINDS = new Set(["phone", "tablet", "quest", "pc", "web", "tv"]);

export interface LinkDeps extends ConnectDeps {
  random?: (bytes: Uint8Array) => Uint8Array;
}

/** A code of [ALPHABET] from unbiased random bytes (32 letters: a byte's low 5 bits). */
export function newCode(random: (b: Uint8Array) => Uint8Array): string {
  return Array.from(random(new Uint8Array(8)), (b) => ALPHABET[b & 31]).join("");
}

export function newSecret(random: (b: Uint8Array) => Uint8Array): string {
  const bytes = random(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** A code typed by hand ("abcd-efgh") as stored, or null. */
export function normalize(value: unknown): string | null {
  const code = typeof value === "string" ? value.toUpperCase().replace(/[^A-Z0-9]/g, "") : "";
  return CODE.test(code) ? code : null;
}

type Row = {
  code: string; device_name: string; device_kind: string; expires_at: string; approved_by: string | null;
  approved_at: string | null; denied_at: string | null; consumed_at: string | null; last_poll_at: string | null;
};

export function createLinkHandler(deps: LinkDeps) {
  const now = deps.now ?? Date.now;
  const random = deps.random ?? ((b: Uint8Array) => crypto.getRandomValues(b));
  const iso = (ms: number) => new Date(ms).toISOString();

  async function find(code: string, extra = ""): Promise<Row | null> {
    const r = await service(deps, "GET", `/rest/v1/ketvoice_device_links?code=eq.${code}${extra}&select=code,device_name,device_kind,expires_at,approved_by,approved_at,denied_at,consumed_at,last_poll_at`);
    if (!r.ok) throw new Error("store");
    const rows = await r.json();
    return Array.isArray(rows) && rows.length ? rows[0] : null;
  }

  async function patch(filter: string, values: Record<string, unknown>): Promise<Row[]> {
    const r = await service(deps, "PATCH", `/rest/v1/ketvoice_device_links?${filter}`, values, { Prefer: "return=representation" });
    if (!r.ok) throw new Error("store");
    return await r.json();
  }

  function state(row: Row): string {
    if (row.consumed_at) return "used";
    if (row.denied_at) return "denied";
    if (Date.parse(row.expires_at) <= now()) return "expired";
    return row.approved_at ? "approved" : "pending";
  }

  async function start(req: Request): Promise<Response> {
    const input = await readBody(req, 2_048);
    if (!input) return json(400, { error: "bad_request" });
    if (!(await take(deps, `link:${clientIp(req)}`, "device-link-start", 10, 600))) return json(429, { error: "rate_limited" });
    await service(deps, "DELETE", `/rest/v1/ketvoice_device_links?expires_at=lt.${iso(now() - 86_400_000)}`).catch(() => null);
    const kind = typeof input.kind === "string" && KINDS.has(input.kind) ? input.kind : "pc";
    for (let attempt = 0; attempt < 3; attempt++) {
      const code = newCode(random);
      const secret = newSecret(random);
      const r = await service(deps, "POST", "/rest/v1/ketvoice_device_links", {
        code, secret_hash: await sha256Hex(secret), device_name: cleanName(input.name), device_kind: kind,
        created_at: iso(now()), expires_at: iso(now() + TTL_S * 1000),
      }, { Prefer: "return=minimal" });
      if (r.status === 409) continue; // the same code is still alive: draw another
      if (!r.ok) return json(503, { error: "unavailable" });
      return json(200, { code, secret, expires_in: TTL_S, interval: POLL_S });
    }
    return json(503, { error: "unavailable" });
  }

  async function poll(req: Request): Promise<Response> {
    const input = await readBody(req, 1_024);
    const code = normalize(input?.code);
    const secret = typeof input?.secret === "string" && SECRET.test(input.secret) ? input.secret : null;
    if (!code || !secret) return json(400, { error: "bad_request" });
    const hash = await sha256Hex(secret);
    const row = await find(code, `&secret_hash=eq.${hash}`);
    if (!row) return json(404, { status: "expired" });
    const status = state(row);
    if (status !== "pending" && status !== "approved") return json(200, { status });
    if (row.last_poll_at && now() - Date.parse(row.last_poll_at) < (POLL_S - 1) * 1000) return json(200, { status: "slow_down" });
    if (status === "pending") {
      await patch(`code=eq.${code}`, { last_poll_at: iso(now()) });
      return json(200, { status: "pending" });
    }
    // Approved: taken once, atomically; whoever loses the race sees "used".
    const taken = await patch(`code=eq.${code}&secret_hash=eq.${hash}&consumed_at=is.null&approved_at=not.is.null&expires_at=gt.${iso(now())}`,
      { consumed_at: iso(now()), last_poll_at: iso(now()) });
    if (!taken.length || !taken[0].approved_by) return json(200, { status: "used" });
    const tokenHash = await signInToken(taken[0].approved_by);
    return tokenHash ? json(200, { status: "approved", token_hash: tokenHash }) : json(200, { status: "denied" });
  }

  /** A one-time sign-in token for [userId] (Auth admin generate_link; nothing is e-mailed). Never for the publisher. */
  async function signInToken(userId: string): Promise<string | null> {
    const u = await service(deps, "GET", `/auth/v1/admin/users/${userId}`);
    if (!u.ok) return null;
    const user = await u.json();
    const email = typeof user?.email === "string" ? user.email : "";
    if (!email || isAdmin(email)) return null;
    const g = await service(deps, "POST", "/auth/v1/admin/generate_link", { type: "magiclink", email });
    if (!g.ok) return null;
    const link = await g.json();
    const hashed = link?.hashed_token ?? link?.properties?.hashed_token;
    return typeof hashed === "string" && /^[A-Za-z0-9_-]{16,200}$/.test(hashed) ? hashed : null;
  }

  async function info(req: Request): Promise<Response> {
    const who = await caller(req, deps);
    if (!who) return json(401, { error: "signed_out" });
    const code = normalize(new URL(req.url).searchParams.get("code"));
    if (!code) return json(400, { error: "bad_request" });
    if (!(await take(deps, `link-user:${who.id}`, "device-link-approve", 20, 600))) return json(429, { error: "rate_limited" });
    const row = await find(code);
    if (!row) return json(404, { status: "unknown" });
    const status = state(row);
    return json(200, { status, device_name: row.device_name, device_kind: row.device_kind,
      expires_in: Math.max(0, Math.round((Date.parse(row.expires_at) - now()) / 1000)) });
  }

  async function decide(req: Request, approve: boolean): Promise<Response> {
    const who = await caller(req, deps);
    if (!who) return json(401, { error: "signed_out" });
    if (approve && isAdmin(who.email)) return json(403, { error: "not_for_this_account" });
    const code = normalize((await readBody(req, 1_024))?.code);
    if (!code) return json(400, { error: "bad_request" });
    if (!(await take(deps, `link-user:${who.id}`, "device-link-approve", 20, 600))) return json(429, { error: "rate_limited" });
    const open = `code=eq.${code}&approved_at=is.null&denied_at=is.null&consumed_at=is.null&expires_at=gt.${iso(now())}`;
    const rows = await patch(open, approve ? { approved_by: who.id, approved_at: iso(now()) } : { denied_at: iso(now()) });
    if (!rows.length) return json(409, { status: "unavailable" });
    return json(200, { status: approve ? "approved" : "denied", device_name: rows[0].device_name });
  }

  return async (req: Request): Promise<Response> => {
    const early = preflight(req);
    if (early) return early;
    try {
      const path = route(req, "device-link");
      if (req.method === "POST" && path === "/start") return await start(req);
      if (req.method === "POST" && path === "/poll") return await poll(req);
      if (req.method === "GET" && path === "/info") return await info(req);
      if (req.method === "POST" && path === "/approve") return await decide(req, true);
      if (req.method === "POST" && path === "/deny") return await decide(req, false);
      return json(404, { error: "not_found" });
    } catch {
      return json(503, { error: "unavailable" });
    }
  };
}
