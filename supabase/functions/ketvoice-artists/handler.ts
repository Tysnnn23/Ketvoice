type ObjectData = Record<string, unknown>;
export type Dependencies = {
  providerKey: () => string;
  quota: (bucket: string, limit: number, windowSeconds: number) => Promise<boolean>;
  fetcher?: typeof fetch;
  now?: () => number;
};

const MBID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, apikey, content-type",
  "access-control-allow-methods": "GET, OPTIONS",
};
const normalize = (name: string) => name.normalize("NFKC").trim().toLowerCase().replace(/\s+/g, " ");
const object = (value: unknown): ObjectData => value && typeof value === "object" && !Array.isArray(value) ? value as ObjectData : {};

function reply(body: unknown, status = 200, retry = false): Response {
  return new Response(JSON.stringify(body), { status, headers: {
    ...CORS, "content-type": "application/json", "cache-control": "no-store",
    ...(retry ? { "retry-after": "60" } : {}),
  } });
}

/** Fixed CDN namespace; identity is checked using Fanart's mbid_id, not filenames. */
export function logoUrl(raw: unknown, mbid: string): string | null {
  if (typeof raw !== "string") return null;
  try {
    const url = new URL(raw);
    if (url.hostname !== "assets.fanart.tv" || !["http:", "https:"].includes(url.protocol) ||
      url.username || url.password || url.port || url.search || url.hash ||
      !MBID.test(mbid) || !url.pathname.startsWith("/fanart/") ||
      !url.pathname.toLowerCase().endsWith(".png")) return null;
    url.protocol = "https:";
    return url.href;
  } catch { return null; }
}

export function musicBrainzArtist(value: unknown, requested: string): { id: string; name: string } | null {
  const root = object(value);
  if (!Array.isArray(root.artists)) return null;
  const matches = root.artists.map(object).filter(row => MBID.test(String(row.id)) &&
    normalize(String(row.name ?? "")) === normalize(requested) && Number(row.score) >= 95)
    .sort((a, b) => Number(b.score) - Number(a.score));
  if (!matches.length || (matches[1] && Number(matches[0].score) - Number(matches[1].score) < 10)) return null;
  return { id: String(matches[0].id).toLowerCase(), name: String(matches[0].name) };
}

export function fanartLogo(value: unknown, id: string): string | null {
  const root = object(value);
  if (String(root.mbid_id ?? "").toLowerCase() !== id.toLowerCase()) return null;
  // Fanart music logos are branded artwork, not public-domain handwriting.
  for (const category of ["hdmusiclogo", "musiclogo"]) {
    if (!Array.isArray(root[category])) continue;
    const assets = (root[category] as unknown[]).map(object)
      .sort((a, b) => Number(b.likes || 0) - Number(a.likes || 0));
    for (const asset of assets) {
      const url = logoUrl(asset.url, id);
      if (url) return url;
    }
  }
  return null;
}

async function readJson(response: Response): Promise<unknown> {
  if (!response.body) throw new Error("Empty provider response");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 1_000_000) throw new Error("Provider response too large");
      chunks.push(value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder().decode(bytes));
}

export function createArtistHandler(deps: Dependencies): (req: Request) => Promise<Response> {
  const fetcher = deps.fetcher ?? fetch;
  const now = deps.now ?? Date.now;
  const cache = new Map<string, { expires: number; body: unknown; status: number }>();
  const pending = new Map<string, Promise<{ body: unknown; status: number }>>();
  const hits = new Map<string, number[]>();
  let cooldown = 0;

  async function lookup(artist: string): Promise<{ body: unknown; status: number }> {
    if (now() < cooldown) return { body: { error: "Artwork temporarily unavailable" }, status: 429 };
    if (!deps.providerKey()) return { body: { error: "Artwork temporarily unavailable" }, status: 503 };
    // Reuse the existing service-role-only quota RPC without changing its schema
    // or any publication access. Provider failure never falls through to the app.
    if (!await deps.quota("artist-logo-minute", 55, 60))
      return { body: { error: "Artwork temporarily unavailable" }, status: 429 };
    // MusicBrainz allows one request per second per IP. A hard refusal here made
    // every burst look like "signatures are broken"; pace briefly instead.
    let spaced = await deps.quota("artist-logo-musicbrainz", 1, 1);
    if (!spaced) {
      await new Promise(resolve => setTimeout(resolve, 1_150));
      spaced = await deps.quota("artist-logo-musicbrainz", 1, 1);
    }
    if (!spaced)
      return { body: { error: "Artwork temporarily unavailable" }, status: 429 };
    const mb = new URL("https://musicbrainz.org/ws/2/artist/");
    mb.searchParams.set("query", `artist:"${artist.replace(/[\\"]/g, "\\$&")}"`);
    mb.searchParams.set("fmt", "json"); mb.searchParams.set("limit", "5");
    const options: RequestInit = { redirect: "error", signal: AbortSignal.timeout(7_000), headers: {
      Accept: "application/json", "User-Agent": "Ketvoice/3.11.1 (https://github.com/Tysnnn23/Ketvoice)",
    } };
    const mbResponse = await fetcher(mb, options);
    if (mbResponse.status === 429 || mbResponse.status === 503) { cooldown = now() + 20_000; return { body: { error: "Artwork temporarily unavailable" }, status: 429 }; }
    if (!mbResponse.ok) return { body: { error: "Artwork temporarily unavailable" }, status: 503 };
    const match = musicBrainzArtist(await readJson(mbResponse), artist);
    if (!match) return { body: { error: "Artist artwork not found", code: "ARTIST_NOT_FOUND" }, status: 404 };
    const endpoint = new URL(`https://webservice.fanart.tv/v3/music/${match.id}`);
    endpoint.searchParams.set("api_key", deps.providerKey());
    // A fresh timeout per provider; no credentials in response objects or logs.
    const response = await fetcher(endpoint, { ...options, signal: AbortSignal.timeout(7_000) });
    if (response.status === 429) { cooldown = now() + 20_000; return { body: { error: "Artwork temporarily unavailable" }, status: 429 }; }
    if (response.status === 404) return { body: { error: "Artist artwork not found" }, status: 404 };
    if (!response.ok) return { body: { error: "Artwork temporarily unavailable" }, status: 503 };
    const url = fanartLogo(await readJson(response), match.id);
    if (!url) return { body: { error: "Artist artwork not found", code: "LOGO_NOT_FOUND" }, status: 404 };
    return { status: 200, body: { artist, mbid: match.id, logo: {
      url, format: "png", kind: "logo", provider: "fanart.tv", source: `https://fanart.tv/artist/${match.id}/`,
    } } };
  }

  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    // The gateway MUST cryptographically verify the bearer token (verify_jwt=true).
    // Do not compare the public apikey header to a legacy environment value:
    // valid project/user JWTs need not use that particular legacy public key.
    if (!req.headers.get("authorization")?.startsWith("Bearer "))
      return reply({ error: "Unauthorized" }, 401);
    if (req.method !== "GET") return reply({ error: "Method not allowed" }, 405);
    const url = new URL(req.url);
    if (url.pathname.endsWith("/health")) return reply({ ok: true, configured: Boolean(deps.providerKey()), version: "3.11.1" });
    if (!url.pathname.endsWith("/logo")) return reply({ error: "Not found" }, 404);
    const artist = (url.searchParams.get("artist") ?? "").trim();
    if (artist.length < 2 || artist.length > 160 || /[\p{C}]/u.test(artist)) return reply({ error: "Invalid artist" }, 400);
    const ip = (req.headers.get("x-forwarded-for")?.split(",")[0] ?? "unknown").trim().slice(0, 64);
    const recent = (hits.get(ip) ?? []).filter(time => now() - time < 60_000);
    if (recent.length >= 12) return reply({ error: "Too many requests" }, 429, true);
    recent.push(now()); hits.set(ip, recent);
    if (hits.size > 1024) hits.delete(hits.keys().next().value!);
    const key = normalize(artist);
    const cached = cache.get(key);
    if (cached && cached.expires > now()) return reply(cached.body, cached.status);
    try {
      let task = pending.get(key);
      if (!task) { task = lookup(artist); pending.set(key, task); }
      const result = await task;
      if (result.status === 200 || result.status === 404) {
        if (cache.size >= 256) cache.delete(cache.keys().next().value!);
        cache.set(key, { ...result, expires: now() + (result.status === 200 ? 86_400_000 : 3_600_000) });
      }
      return reply(result.body, result.status, result.status === 429);
    } catch { return reply({ error: "Artwork temporarily unavailable" }, 503); }
    finally { pending.delete(key); }
  };
}
