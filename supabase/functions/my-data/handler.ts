// "Mes donnees": GET /export gathers every row of the caller's account with the caller's own session (row level
// security applies; each table is filtered on the caller's column), plus what only the service role may read about
// them (the Last.fm user name, never its session key; the names of their photos). POST /delete erases the account
// with the service role after the typed confirmation: the photos first (Storage refuses direct deletes), then the
// auth user, whose rows go by cascade. The publishing account can never be deleted here.
import { asUser, caller, type ConnectDeps, body as readBody, isAdmin, json, preflight, route, service, take } from "../_shared/connect.ts";

export const CONFIRM = "SUPPRIMER";

/** The caller's tables and the column naming them. Missing tables (an older project) are reported, not fatal. */
export const TABLES: Array<[table: string, column: string]> = [
  ["ketvoice_profiles", "id"],
  ["ketvoice_library", "user_id"],
  ["ketvoice_devices", "user_id"],
  ["ketvoice_shared_playlists", "owner_id"],
  ["ketvoice_shared_members", "user_id"],
  ["ketvoice_shared_tracks", "added_by"],
  ["ketvoice_shared_invites", "created_by"],
  ["ketvoice_public_playlists", "owner_id"],
  ["ketvoice_follows", "follower"],
  ["ketvoice_blocks", "blocker"],
  ["ketvoice_activity", "actor"],
  ["ketvoice_reactions", "user_id"],
  ["ketvoice_profile_modules", "user_id"],
  ["ketvoice_friendships", "requester,addressee"],
  ["ketvoice_presence", "user_id"],
  ["ketvoice_link_playlists", "owner_id"],
];

export const BUCKETS = ["ketvoice-avatars", "ketvoice-public-avatars"];

export function createMyDataHandler(deps: ConnectDeps) {
  const now = deps.now ?? Date.now;

  async function rows(token: string, table: string, column: string, uid: string): Promise<unknown> {
    // "a,b": rows where the listener is in either column (a friendship has two sides).
    const filter = column.includes(",") ? `or=(${column.split(",").map((c) => `${c}.eq.${uid}`).join(",")})` : `${column}=eq.${uid}`;
    const r = await asUser(deps, token, `/rest/v1/${table}?select=*&${filter}&limit=10000`);
    if (!r.ok) return { unavailable: r.status };
    return await r.json();
  }

  async function objects(bucket: string, uid: string): Promise<string[]> {
    const r = await service(deps, "POST", `/storage/v1/object/list/${bucket}`, { prefix: `${uid}/`, limit: 1000 });
    if (!r.ok) return [];
    const list = await r.json();
    return Array.isArray(list) ? list.map((o) => `${uid}/${o?.name}`).filter((n) => !n.endsWith("/undefined")) : [];
  }

  async function exportAll(req: Request): Promise<Response> {
    const who = await caller(req, deps);
    if (!who) return json(401, { error: "signed_out" });
    if (!(await take(deps, `my-data:${who.id}`, "my-data-export", 10, 3600))) return json(429, { error: "rate_limited" });
    const tables: Record<string, unknown> = {};
    for (const [table, column] of TABLES) tables[table] = await rows(who.token, table, column, who.id);
    const fm = await service(deps, "GET", `/rest/v1/ketvoice_lastfm_links?user_id=eq.${who.id}&select=username,created_at`);
    const fmRows = fm.ok ? await fm.json() : [];
    const photos: Record<string, string[]> = {};
    for (const b of BUCKETS) photos[b] = await objects(b, who.id);
    return json(200, {
      format: "ketvoice-my-data-v1",
      exported_at: new Date(now()).toISOString(),
      account: { id: who.id, email: who.email },
      tables,
      lastfm: Array.isArray(fmRows) && fmRows.length ? { username: fmRows[0].username, linked_at: fmRows[0].created_at } : null,
      photos,
    });
  }

  async function erase(req: Request): Promise<Response> {
    const who = await caller(req, deps);
    if (!who) return json(401, { error: "signed_out" });
    if (isAdmin(who.email)) return json(403, { error: "not_for_this_account" });
    if ((await readBody(req, 1_024))?.confirm !== CONFIRM) return json(400, { error: "confirm" });
    if (!(await take(deps, `my-data:${who.id}`, "my-data-delete", 5, 3600))) return json(429, { error: "rate_limited" });
    // The address may have changed since the token was issued: Auth's own record decides, once more, server side.
    const u = await service(deps, "GET", `/auth/v1/admin/users/${who.id}`);
    if (!u.ok) return json(503, { error: "unavailable" });
    const user = await u.json();
    if (isAdmin(user?.email) || isAdmin(user?.new_email)) return json(403, { error: "not_for_this_account" });
    for (const b of BUCKETS) {
      const names = await objects(b, who.id);
      if (names.length) {
        const d = await service(deps, "DELETE", `/storage/v1/object/${b}`, { prefixes: names });
        if (!d.ok) return json(503, { error: "unavailable" });
      }
    }
    const del = await service(deps, "DELETE", `/auth/v1/admin/users/${who.id}`);
    if (!del.ok) return json(503, { error: "unavailable" });
    return json(200, { status: "deleted" });
  }

  return async (req: Request): Promise<Response> => {
    const early = preflight(req);
    if (early) return early;
    try {
      const path = route(req, "my-data");
      if (req.method === "GET" && path === "/export") return await exportAll(req);
      if (req.method === "POST" && path === "/delete") return await erase(req);
      return json(404, { error: "not_found" });
    } catch {
      return json(503, { error: "unavailable" });
    }
  };
}
