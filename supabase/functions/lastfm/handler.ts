// Last.fm for Ketvoice accounts. The API key and the shared secret are edge-function secrets (LASTFM_API_KEY,
// LASTFM_SHARED_SECRET); the client never holds them. Linking follows Last.fm's desktop flow: /auth/start gets a token
// (auth.getToken) and the address where the listener allows Ketvoice, /auth/complete exchanges it (auth.getSession)
// and keeps the session key in ketvoice_lastfm_links (service role only). /nowplaying and /scrobble sign each call
// with that key. Every route needs the listener's own Ketvoice session.
import { caller, type ConnectDeps, body as readBody, json, preflight, route, service, take } from "../_shared/connect.ts";
import { md5 } from "./md5.ts";

export const API = "https://ws.audioscrobbler.com/2.0/";
const MAX_BATCH = 50;

export interface LastFmDeps extends ConnectDeps {
  apiKey: () => string;
  secret: () => string;
}

/** Last.fm's api_sig: every parameter but format/callback, sorted by name, name+value, then the secret; MD5 in hex. */
export function sign(params: Record<string, string>, secret: string): string {
  const text = Object.keys(params).filter((k) => k !== "format" && k !== "callback").sort().map((k) => k + params[k]).join("");
  return md5(text + secret);
}

export type Listen = { title: string; artist: string; album: string; durationMs: number; at: number };

/** A listen as the client sends it (ScrobbleQueue.json), or null when Last.fm could not take it. */
export function listen(value: unknown): Listen | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  const text = (x: unknown) => (typeof x === "string" ? x.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 500) : "");
  const title = text(v.title), artist = text(v.artist);
  const durationMs = Number(v.durationMs) || 0;
  const at = Number(v.at) || 0;
  if (!title || !artist || (durationMs > 0 && durationMs < 30_000)) return null;
  return { title, artist, album: text(v.album), durationMs, at };
}

/** track.scrobble's indexed parameters for a batch. */
export function scrobbleParams(items: Listen[], sk: string, apiKey: string): Record<string, string> {
  const p: Record<string, string> = { method: "track.scrobble", api_key: apiKey, sk };
  items.forEach((it, i) => {
    p[`artist[${i}]`] = it.artist;
    p[`track[${i}]`] = it.title;
    p[`timestamp[${i}]`] = String(Math.floor(it.at / 1000));
    if (it.album) p[`album[${i}]`] = it.album;
    if (it.durationMs > 0) p[`duration[${i}]`] = String(Math.round(it.durationMs / 1000));
  });
  return p;
}

export function createLastFmHandler(deps: LastFmDeps) {
  const now = deps.now ?? Date.now;

  async function call(params: Record<string, string>): Promise<{ ok: boolean; error: number; data: Record<string, unknown> }> {
    const signed = { ...params, api_sig: sign(params, deps.secret()), format: "json" };
    const r = await deps.fetcher(API, {
      method: "POST", body: new URLSearchParams(signed).toString(), redirect: "error", signal: AbortSignal.timeout(8_000),
      headers: { "Content-Type": "application/x-www-form-urlencoded", "User-Agent": "Ketvoice/2.0" },
    });
    const data = await r.json().catch(() => ({}));
    const error = typeof data?.error === "number" ? data.error : (r.ok ? 0 : -1);
    return { ok: r.ok && error === 0, error, data };
  }

  async function link(uid: string): Promise<Record<string, string | null> | null> {
    const r = await service(deps, "GET", `/rest/v1/ketvoice_lastfm_links?user_id=eq.${uid}&select=username,session_key,pending_token,pending_at`);
    if (!r.ok) throw new Error("store");
    const rows = await r.json();
    return Array.isArray(rows) && rows.length ? rows[0] : null;
  }

  async function keep(uid: string, values: Record<string, unknown>) {
    const r = await service(deps, "POST", "/rest/v1/ketvoice_lastfm_links?on_conflict=user_id",
      { user_id: uid, ...values, updated_at: new Date(now()).toISOString() }, { Prefer: "return=minimal,resolution=merge-duplicates" });
    if (!r.ok) throw new Error("store");
  }

  const forget = (uid: string) => service(deps, "DELETE", `/rest/v1/ketvoice_lastfm_links?user_id=eq.${uid}`);

  /** A Last.fm error code to an answer: 9 the link is gone, 11/16 try later, 29 too fast, the rest refused. */
  async function failure(uid: string, error: number): Promise<Response> {
    if (error === 9) { await forget(uid); return json(409, { status: "not_linked" }); }
    if (error === 29) return json(429, { error: "rate_limited" });
    if (error === 11 || error === 16 || error === -1) return json(503, { error: "unavailable" });
    return json(400, { error: "refused", code: error });
  }

  async function start(uid: string): Promise<Response> {
    const got = await call({ method: "auth.getToken", api_key: deps.apiKey() });
    const token = got.data?.token;
    if (!got.ok || typeof token !== "string" || !/^[A-Za-z0-9]{8,128}$/.test(token)) return json(503, { error: "unavailable" });
    await keep(uid, { pending_token: token, pending_at: new Date(now()).toISOString() });
    return json(200, { url: `https://www.last.fm/api/auth/?api_key=${encodeURIComponent(deps.apiKey())}&token=${token}` });
  }

  async function complete(uid: string): Promise<Response> {
    const row = await link(uid);
    const token = row?.pending_token;
    if (!token || now() - Date.parse(row?.pending_at ?? "") > 60 * 60_000) return json(410, { status: "expired" });
    const got = await call({ method: "auth.getSession", api_key: deps.apiKey(), token });
    if (got.error === 14) return json(409, { status: "not_authorized" });
    const session = got.data?.session as Record<string, unknown> | undefined;
    if (!got.ok || typeof session?.key !== "string" || typeof session?.name !== "string") return json(got.error === 4 || got.error === 15 ? 410 : 503, { status: "failed" });
    const name = session.name.replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 64);
    await keep(uid, { username: name, session_key: session.key, pending_token: null, pending_at: null });
    return json(200, { name });
  }

  async function nowPlaying(uid: string, input: Record<string, unknown>): Promise<Response> {
    const sk = (await link(uid))?.session_key;
    if (!sk) return json(409, { status: "not_linked" });
    const t = listen({ ...(input.track as object), at: 1 });
    if (!t) return json(400, { error: "bad_request" });
    const p: Record<string, string> = { method: "track.updateNowPlaying", api_key: deps.apiKey(), sk, artist: t.artist, track: t.title };
    if (t.album) p.album = t.album;
    if (t.durationMs > 0) p.duration = String(Math.round(t.durationMs / 1000));
    const got = await call(p);
    return got.ok ? json(200, { status: "ok" }) : await failure(uid, got.error);
  }

  async function scrobble(uid: string, input: Record<string, unknown>): Promise<Response> {
    const sk = (await link(uid))?.session_key;
    if (!sk) return json(409, { status: "not_linked" });
    const raw = Array.isArray(input.scrobbles) ? input.scrobbles.slice(0, MAX_BATCH) : [];
    const items = raw.map(listen).filter((x): x is Listen => x !== null && x.at > 0 && x.at <= now() + 300_000);
    if (!items.length) return json(400, { error: "bad_request" });
    const got = await call(scrobbleParams(items, sk, deps.apiKey()));
    if (!got.ok) return await failure(uid, got.error);
    const attr = ((got.data?.scrobbles as Record<string, unknown>)?.["@attr"] ?? {}) as Record<string, unknown>;
    return json(200, { accepted: Number(attr.accepted ?? items.length), ignored: Number(attr.ignored ?? 0) });
  }

  return async (req: Request): Promise<Response> => {
    const early = preflight(req);
    if (early) return early;
    try {
      if (req.method !== "POST") return json(405, { error: "method" });
      const who = await caller(req, deps);
      if (!who) return json(401, { error: "signed_out" });
      if (!deps.apiKey() || !deps.secret()) return json(503, { error: "not_configured" });
      const path = route(req, "lastfm");
      const input = (await readBody(req)) ?? {};
      if (!(await take(deps, `lastfm:${who.id}`, path === "/scrobble" || path === "/nowplaying" ? "lastfm-send" : "lastfm-auth",
        path === "/scrobble" || path === "/nowplaying" ? 120 : 20, 600))) return json(429, { error: "rate_limited" });
      switch (path) {
        case "/auth/start": return await start(who.id);
        case "/auth/complete": return await complete(who.id);
        case "/status": {
          const row = await link(who.id);
          return json(200, { connected: !!row?.session_key, name: row?.session_key ? row?.username ?? "" : "" });
        }
        case "/disconnect": await forget(who.id); return json(200, { status: "ok" });
        case "/nowplaying": return await nowPlaying(who.id, input);
        case "/scrobble": return await scrobble(who.id, input);
        default: return json(404, { error: "not_found" });
      }
    } catch {
      return json(503, { error: "unavailable" });
    }
  };
}
