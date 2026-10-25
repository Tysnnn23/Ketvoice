

type Json = Record<string, unknown>;

const SECRETS_URL =
  "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json";
const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
const SPOTIFY_CLIENT_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)";
const PATHFINDER_HASH = "75bbf6bfcfdf85b8fc828417bfad92b7cd66bf7f556d85670f4da8292373ebec";

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};

const ADDONS = [
  { tag: "um", env: "ADDON_ULTRAMAX", label: "Ultra MAX" },
  { tag: "last", env: "ADDON_LASTBABE", label: "Last Babe" },
] as const;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...CORS,
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function env(name: string): string {
  return (Deno.env.get(name) ?? "").trim();
}

function supabaseUrl(): string {
  return env("SUPABASE_URL").replace(/\/$/, "");
}

function serviceRole(): string {
  return env("SUPABASE_SERVICE_ROLE_KEY");
}

function cleanBase(value: string): string {
  return value.trim().replace(/\/+$/, "");
}

async function request(
  url: string,
  init: RequestInit = {},
  timeoutMs = 15_000,
): Promise<Response> {
  return await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
}

async function jsonRequest(
  url: string,
  init: RequestInit = {},
  timeoutMs = 15_000,
): Promise<unknown | null> {
  const res = await request(url, init, timeoutMs);
  if (!res.ok) return null;
  try {
    return await res.json();
  } catch {
    return null;
  }
}

const secretCache = new Map<string, { value: string | null; at: number }>();

async function secret(name: string): Promise<string | null> {
  const cached = secretCache.get(name);
  if (cached && Date.now() - cached.at < 5 * 60_000) return cached.value;

  const base = supabaseUrl();
  const key = serviceRole();
  if (!base || !key) return null;

  const res = await request(
    `${base}/rest/v1/rpc/ketvoice_get_secret`,
    {
      method: "POST",
      headers: {
        apikey: key,
        Authorization: `Bearer ${key}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ p_name: name }),
    },
    10_000,
  );
  let value: string | null = null;
  if (res.ok) {
    const text = await res.text();
    try {
      const parsed = JSON.parse(text);
      value = typeof parsed === "string" ? parsed : null;
    } catch {
      value = text.trim() || null;
    }
  }
  secretCache.set(name, { value, at: Date.now() });
  return value;
}

async function addonBases(): Promise<Array<{ tag: string; base: string; label: string }>> {
  const rows = await Promise.all(
    ADDONS.map(async (item) => ({
      tag: item.tag,
      base: (await secret(item.env)) ?? "",
      label: item.label,
    })),
  );
  return rows.filter((row) => row.base.length > 0).map((row) => ({
    ...row,
    base: cleanBase(row.base),
  }));
}

async function managedSources(): Promise<Source[]> {
  const root = Deno.env.get("SUPABASE_URL")?.replace(/\/$/,"");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if(!root || !key) return [];
  const response = await fetch(`${root}/rest/v1/ketvoice_managed_sources?enabled=eq.true&select=id,name,manifest_url,enabled&limit=30`,
    {headers:{apikey:key,Authorization:`Bearer ${key}`},signal:AbortSignal.timeout(5000)});
  if(!response.ok) throw new Error("Sources indisponibles");
  return await response.json();
}

async function searchManaged(source: Source, query: string): Promise<Json[]> {
  const config=await manifest(source.manifest_url);
  const data=await remoteJson(fill(config.search,"query",query));
  return firstArray(data).map(item=>normaliseTrack("um",item)).filter((item):item is Json=>!!item)
    .map(item=>({ ...item, id:`kv:um:managed:${source.id}:${item.addonId}`,
      addonId:`managed:${source.id}:${item.addonId}`,source:"um",sourceLabel:source.name }));
}

async function streamManaged(source: Source, id: string, title: string, artist: string): Promise<Json | null> {
  const config=await manifest(source.manifest_url);
  let trackId=id;
  if(!trackId && title) {
    const match=bestAddonMatch(await searchManaged(source,`${title} ${artist}`),title,artist);
    trackId=String(match?.addonId ?? "").replace(new RegExp(`^managed:${source.id}:`),"");
  }
  if(!trackId) return null;
  const target=await publicUrl(fill(config.stream,"id",trackId));
  const response=await fetch(target,{redirect:"manual",signal:AbortSignal.timeout(12000)});
  if(!response.ok || Number(response.headers.get("content-length") || 0)>65536) return null;
  const raw=(await response.text()).slice(0,65536);
  let data: unknown=raw;
  try {data=JSON.parse(raw);} catch {data=raw;}
  const obj=data && typeof data==="object" ? data as Json : {};
  const nested=obj.data && typeof obj.data==="object" ? obj.data as Json : {};
  const options=firstArray(obj.sources ?? obj.streams ?? nested.sources ?? nested.streams);
  const first=options.find(item=>typeof item==="string" || item && typeof item==="object");
  const candidate=first && typeof first==="object" ? first as Json : {};
  const link=text(obj.url ?? obj.stream ?? obj.link ?? obj.audio ?? nested.url ?? nested.stream ??
    candidate.url ?? candidate.stream ?? candidate.link ?? candidate.audio ??
    (typeof first==="string" ? first : typeof data==="string" ? data : ""));
  if(!link) return null;
  const stream=link.startsWith("/") ? new URL(link,target).toString() : link;
  if(!stream.startsWith("https://")) return null;
  return {url:stream,format:text(obj.format ?? nested.format) ?? inferFormat(stream),quality:"Haute qualité",source:`src-${source.id}`,addonId:trackId};
}

function text(value: unknown): string | null {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (typeof value === "number") return String(value);
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = text(item);
      if (found) return found;
    }
  }
  if (value && typeof value === "object") {
    const obj = value as Json;
    for (const key of ["name", "title", "label", "artistName", "artist"]) {
      const found = text(obj[key]);
      if (found) return found;
    }
  }
  return null;
}

function number(value: unknown): number {
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : 0;
}

function firstArray(value: unknown): unknown[] {
  if (Array.isArray(value)) return value;
  if (value && typeof value === "object") {
    const obj = value as Json;
    for (const key of ["tracks", "results", "data", "songs", "items", "streams"]) {
      const nested = obj[key];
      if (Array.isArray(nested)) return nested;
      if (nested && typeof nested === "object") {
        const deeper = firstArray(nested);
        if (deeper.length > 0) return deeper;
      }
    }
  }
  return [];
}

function normalise(value: string): string {
  return value
    .toLowerCase()
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/\([^)]*\)|\[[^\]]*\]/g, " ")
    .replace(/[^a-z0-9\u00e0-\u00ff ]/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function inferFormat(url: string): string {
  const lower = url.toLowerCase();
  if (lower.includes(".flac") || lower.includes("flac")) return "flac";
  if (lower.includes(".wav")) return "wav";
  if (lower.includes(".m4a") || lower.includes("aac")) return "aac";
  if (lower.includes(".opus")) return "opus";
  return "mp3";
}

const searchCache = new Map<string, { at: number; rows: Json[] }>();
const addonCooldown = new Map<string, number>();

async function searchAddon(tag: string, base: string, query: string): Promise<Json[]> {

  const cooldownUntil = addonCooldown.get(tag) ?? 0;
  if (Date.now() < cooldownUntil) return [];

  const cacheKey = `${tag}|${query.toLowerCase()}`;
  const cached = searchCache.get(cacheKey);
  if (cached && Date.now() - cached.at < 60_000) return cached.rows;

  let res: Response;
  try {
    res = await request(
      `${cleanBase(base)}/search?q=${encodeURIComponent(query)}`,
      {
        headers: {
          Accept: "application/json, text/plain, */*",
          "User-Agent": UA,
        },
      },
      8_000,
    );
  } catch {
    return [];
  }
  if (res.status === 429) {
    addonCooldown.set(tag, Date.now() + 5 * 60_000);
    return [];
  }
  if (!res.ok) return [];

  let data: unknown = null;
  try {
    data = await res.json();
  } catch {
    return [];
  }
  const rows = firstArray(data)
    .map((value) => normaliseTrack(tag, value))
    .filter((value): value is Json => !!value);
  searchCache.set(cacheKey, { at: Date.now(), rows });
  return rows;
}

function normaliseTrack(tag: string, value: unknown): Json | null {
  if (!value || typeof value !== "object") return null;
  const root = value as Json;
  const nested = (root.track ?? root.song ?? root) as Json;
  const addonId = text(nested.id ?? nested.trackId ?? nested.deezerId ?? nested.isrc);
  const title = text(nested.title ?? nested.name ?? nested.track);
  if (!addonId || !title) return null;

  const albumValue = nested.album;
  const album = typeof albumValue === "object" && albumValue
    ? text((albumValue as Json).title ?? (albumValue as Json).name)
    : text(albumValue);
  const artist = text(nested.artist ?? nested.artistName ?? nested.artists ?? nested.author) ?? "";
  const artwork = text(
    nested.artworkURL ?? nested.artwork ?? nested.coverUrl ?? nested.cover ??
      nested.image ?? nested.thumbnail ?? nested.picture,
  ) ?? "";
  const durationMs = number(nested.durationMs) ||
    (number(nested.duration) > 1_000 ? number(nested.duration) : number(nested.duration) * 1_000);

  return {
    id: `kv:${tag}:${addonId}`,
    addonId,
    source: tag,
    title,
    artist,
    album: album ?? "",
    durationMs: Math.round(durationMs),
    artworkURL: artwork.startsWith("https://") ? artwork : undefined,
    isrc: text(nested.isrc) ?? undefined,
  };
}

function bestAddonMatch(rows: Json[], title: string, artist: string): Json | null {
  const wantTitle = normalise(title);
  const wantArtist = normalise(artist.split(",")[0] ?? "");
  let best: Json | null = null;
  let bestScore = -1;
  for (const row of rows) {
    const candidateTitle = normalise(String(row.title ?? ""));
    const candidateArtist = normalise(String(row.artist ?? ""));
    if (!candidateTitle || !wantTitle) continue;
    if (!(candidateTitle === wantTitle || candidateTitle.includes(wantTitle) ||
      wantTitle.includes(candidateTitle))) continue;
    if (wantArtist && !(candidateArtist.includes(wantArtist) || wantArtist.includes(candidateArtist))) {
      continue;
    }
    let score = candidateTitle === wantTitle ? 5 : 2;
    if (wantArtist && candidateArtist.includes(wantArtist)) score += 3;
    if (score > bestScore) {
      best = row;
      bestScore = score;
    }
  }
  return bestScore >= 5 ? best : null;
}

async function streamAddon(
  tag: string,
  base: string,
  id: string,
  title: string,
  artist: string,
): Promise<Json | null> {
  let addonId = id.trim();
  if (!addonId && title.trim()) {
    const rows = await searchAddon(tag, base, `${title} ${artist.split(",")[0] ?? ""}`.trim());
    addonId = String(bestAddonMatch(rows, title, artist)?.addonId ?? "");
  }
  if (!addonId) return null;

  const endpoint = `${cleanBase(base)}/stream/${encodeURIComponent(addonId)}`;
  const res = await request(endpoint, {
    headers: { Accept: "application/json, text/plain, */*", "User-Agent": UA },
  }, 20_000);
  if (!res.ok) return null;
  const raw = await res.text();
  let data: unknown = raw.trim();
  try {
    data = JSON.parse(raw);
  } catch {

  }
  const obj = data && typeof data === "object" ? data as Json : {};
  const nested = (obj.data ?? obj) as Json;
  let streamUrl = text(
    obj.url ?? obj.stream ?? obj.link ?? obj.audio ?? obj.download ??
      nested.url ?? nested.stream ?? nested.link ?? nested.audio ?? nested.download,
  ) ?? "";
  if (!streamUrl && typeof data === "string") streamUrl = data.trim();
  if (!streamUrl) return null;
  if (streamUrl.startsWith("/")) streamUrl = `${cleanBase(base)}${streamUrl}`;
  if (!streamUrl.startsWith("https://")) return null;

  const format = text(obj.format ?? obj.codec ?? obj.container ?? nested.format ?? nested.codec) ??
    inferFormat(streamUrl);
  const quality = text(obj.quality ?? obj.streamQuality ?? obj.audioQuality ?? nested.quality) ??
    (format === "flac" || format === "wav" ? "Sans perte" : "Haute qualité");
  return { url: streamUrl, format, quality, source: tag, addonId };
}

type SpotifyToken = { token: string; expiresAt: number };
let spotifyTokenCache: SpotifyToken | null = null;
let spotifySecretsCache: { version: string; key: Uint8Array; at: number } | null = null;

async function spotifyToken(): Promise<string | null> {
  if (spotifyTokenCache && Date.now() < spotifyTokenCache.expiresAt - 30_000) {
    return spotifyTokenCache.token;
  }
  const spDc = await secret("SP_DC");
  if (!spDc) return null;

  const keyInfo = await spotifySecrets();
  if (!keyInfo) return null;
  const code = await totp(keyInfo.key);
  const url = "https://open.spotify.com/api/token?reason=init&productType=mobile-web-player" +
    `&totp=${code}&totpVer=${keyInfo.version}&totpServer=${code}`;
  const res = await request(url, {
    headers: {
      "User-Agent": UA,
      Origin: "https://open.spotify.com",
      Referer: "https://open.spotify.com/",
      Cookie: `sp_dc=${spDc}`,
    },
  }, 20_000);
  if (!res.ok) return null;
  const data = await res.json() as Json;
  const token = text(data.accessToken);
  if (!token) return null;
  const expiry = number(data.accessTokenExpirationTimestampMs) || Date.now() + 3_600_000;
  spotifyTokenCache = { token, expiresAt: expiry };
  return token;
}

async function spotifySecrets(): Promise<{ version: string; key: Uint8Array } | null> {
  if (spotifySecretsCache && Date.now() - spotifySecretsCache.at < 3_600_000) {
    return { version: spotifySecretsCache.version, key: spotifySecretsCache.key };
  }
  const data = await jsonRequest(SECRETS_URL, { headers: { "User-Agent": UA } }, 15_000);
  if (!data || typeof data !== "object") return null;
  const obj = data as Record<string, unknown>;
  const version = Object.keys(obj).filter((key) => /^\d+$/.test(key))
    .sort((a, b) => Number(b) - Number(a))[0];
  if (!version || !Array.isArray(obj[version])) return null;
  const values = obj[version] as number[];
  let textKey = "";
  values.forEach((value, index) => {
    textKey += (Number(value) ^ ((index % 33) + 9)).toString();
  });
  const key = new TextEncoder().encode(textKey);
  spotifySecretsCache = { version, key, at: Date.now() };
  return { version, key };
}

async function totp(key: Uint8Array): Promise<string> {
  const counter = Math.floor(Date.now() / 1000 / 30);
  const data = new Uint8Array(8);
  const view = new DataView(data.buffer);
  view.setUint32(4, counter);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new Uint8Array(key),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const hash = new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, data));
  const offset = hash[19] & 0x0f;
  const binary = ((hash[offset] & 0x7f) << 24) |
    ((hash[offset + 1] & 0xff) << 16) |
    ((hash[offset + 2] & 0xff) << 8) |
    (hash[offset + 3] & 0xff);
  return String(binary % 1_000_000).padStart(6, "0");
}

async function spotifyIdFromSearch(title: string, artist: string): Promise<string | null> {
  const token = await spotifyToken();
  if (!token) return null;
  const variables = {
    searchTerm: `${title} ${artist.split(",")[0] ?? ""}`.trim(),
    offset: 0,
    limit: 10,
    numberOfTopResults: 5,
  };
  const extensions = {
    persistedQuery: { version: 1, sha256Hash: PATHFINDER_HASH },
  };
  const url = "https://api-partner.spotify.com/pathfinder/v1/query?operationName=searchDesktop" +
    `&variables=${encodeURIComponent(JSON.stringify(variables))}` +
    `&extensions=${encodeURIComponent(JSON.stringify(extensions))}`;
  const data = await jsonRequest(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
      "App-Platform": "WebPlayer",
      Origin: "https://open.spotify.com",
      Referer: "https://open.spotify.com/",
      "User-Agent": UA,
    },
  }, 20_000);
  if (!data) return null;
  const candidates = new Map<string, Json>();
  const visit = (node: unknown): void => {
    if (!node) return;
    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }
    if (typeof node !== "object") return;
    const obj = node as Json;
    const uri = text(obj.uri);
    if (uri?.startsWith("spotify:track:") && text(obj.name)) candidates.set(uri, obj);
    Object.values(obj).forEach(visit);
  };
  visit(data);
  if (candidates.size === 0) return null;
  const wantTitle = normalise(title);
  const wantArtist = normalise(artist.split(",")[0] ?? "");
  let best: { id: string; score: number } | null = null;
  for (const [uri, obj] of candidates) {
    const candidateTitle = normalise(String(obj.name ?? ""));
    const haystack = normalise(JSON.stringify(obj));
    let score = candidateTitle === wantTitle ? 5 : 2;
    if (wantArtist && haystack.includes(wantArtist)) score += 3;
    if (!best || score > best.score) best = { id: uri.replace("spotify:track:", ""), score };
  }
  return best && best.score >= 5 ? best.id : null;
}

async function spotifyIdFromGenius(title: string, artist: string): Promise<string | null> {
  const query = encodeURIComponent(`${title} ${artist}`);
  const data = await jsonRequest(`https://genius.com/api/search/multi?q=${query}`, {
    headers: { "User-Agent": UA },
  }, 15_000);
  if (!data || typeof data !== "object") return null;
  const sections = ((data as Json).response as Json | undefined)?.sections;
  if (!Array.isArray(sections)) return null;
  const hits: Array<{ url: string; title: string; artist: string }> = [];
  for (const section of sections) {
    if (!section || typeof section !== "object") continue;
    const list = (section as Json).hits;
    if (!Array.isArray(list)) continue;
    for (const item of list) {
      if (!item || typeof item !== "object") continue;
      const hit = item as Json;
      if (hit.type !== "song") continue;
      const result = hit.result as Json | undefined;
      if (!result) continue;
      const url = text(result.url);
      const hitTitle = text(result.title);
      if (url && hitTitle) {
        hits.push({ url, title: hitTitle, artist: text(result.primary_artist_names ?? result.primary_artist) ?? "" });
      }
    }
  }
  if (hits.length === 0) return null;
  const wantTitle = normalise(title);
  const wantArtist = normalise(artist);
  const best = hits.sort((a, b) => {
    const score = (hit: { title: string; artist: string }) => {
      let value = normalise(hit.title) === wantTitle ? 4 : 1;
      if (normalise(hit.artist).includes(wantArtist)) value += 3;
      return value;
    };
    return score(b) - score(a);
  })[0];
  if (!best) return null;
  const page = await request(best.url, { headers: { "User-Agent": UA } }, 20_000);
  if (!page.ok) return null;
  const html = await page.text();
  return /open\.spotify\.com\/(?:embed\/)?track\/([A-Za-z0-9]{10,})/.exec(html)?.[1] ?? null;
}

async function canvasForSpotifyId(spotifyId: string): Promise<string | null> {
  const token = await spotifyToken();
  if (!token) return null;
  const uri = new TextEncoder().encode(`spotify:track:${spotifyId}`);
  const inner = new Uint8Array(2 + uri.length);
  inner[0] = 0x0a;
  inner[1] = uri.length;
  inner.set(uri, 2);
  const body = new Uint8Array(2 + inner.length);
  body[0] = 0x0a;
  body[1] = inner.length;
  body.set(inner, 2);
  const res = await request("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases", {
    method: "POST",
    headers: {
      Accept: "application/protobuf",
      "Accept-Language": "en",
      "Accept-Encoding": "gzip",
      "Content-Type": "application/x-www-form-urlencoded",
      "User-Agent": SPOTIFY_CLIENT_UA,
      Authorization: `Bearer ${token}`,
    },
    body,
  }, 25_000);
  if (!res.ok) return null;
  const bytes = new Uint8Array(await res.arrayBuffer());
  const text = new TextDecoder("iso-8859-1").decode(bytes);
  return /https:\/\/[\x21-\x7E]+\.cnvs\.mp4/.exec(text)?.[0] ?? null;
}

async function resolveCanvas(
  title: string,
  artist: string,
  spotifyId: string,
): Promise<Json | null> {
  const id = spotifyId || (await spotifyIdFromSearch(title, artist)) ||
    (await spotifyIdFromGenius(title, artist));
  if (!id) return null;
  const url = await canvasForSpotifyId(id);
  if (!url) return null;
  return { url, spotifyId: id, source: "Ketvoice API" };
}

async function health(): Promise<Response> {
  const [spDc, um, last] = await Promise.all([
    secret("SP_DC"),
    secret("ADDON_ULTRAMAX"),
    secret("ADDON_LASTBABE"),
  ]);
  return json({
    ok: true,
    name: "Ketvoice API",
    version: "2.6.0",
    backend: "Supabase Edge Function",
    secrets: {
      spotify: !!spDc,
      ultraMax: !!um,
      lastBabe: !!last,
    },
  });
}

function sourceManifest(): Response {
  const base = `${supabaseUrl()}/functions/v1/ketvoice-api`;
  return json({
    name: "Ketvoice API",
    description: "Recherche, streaming et canvas Ketvoice via Supabase.",
    baseUrl: base,
    search: "/search?q={query}",
    stream: "/stream?source=um&id={id}&quality={quality}",
    download: "/stream?source=um&id={id}&quality={quality}",
    qualities: ["high", "low"],
    allowDownloads: true,
  });
}

async function searchRoute(url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") ?? "").trim();
  if (q.length < 2) return json({ name: "Ketvoice API", tracks: [] });
  const [bases,sources] = await Promise.all([addonBases(),managedSources().catch(()=>[])]);
  const results = await Promise.allSettled([
    ...bases.map((item) => searchAddon(item.tag, item.base, q)),
    ...sources.map((item) => searchManaged(item,q)),
  ]);
  const tracks = results.flatMap((result) => result.status === "fulfilled" ? result.value : []);
  const unique = new Map<string, Json>();
  for (const track of tracks) {
    const key = String(track.id ?? "");
    if (key && !unique.has(key)) unique.set(key, track);
  }
  return json({ name: "Ketvoice API", tracks: [...unique.values()] });
}

async function streamRoute(url: URL): Promise<Response> {
  const source = (url.searchParams.get("source") ?? "um").trim();
  const id = (url.searchParams.get("id") ?? "").trim();
  const title = (url.searchParams.get("title") ?? "").trim();
  const artist = (url.searchParams.get("artist") ?? "").trim();
  const managedId=/^managed:([0-9a-f-]{36}):(.*)$/s.exec(id);
  if(source==="um" && managedId) {
    const item=(await managedSources()).find(row=>row.id===managedId[1]);
    if(!item) return json({error:"Source inconnue"},400);
    const stream=await streamManaged(item,managedId[2],title,artist).catch(()=>null);
    return stream ? json(stream) : json({error:"Flux indisponible"},404);
  }
  if(/^src-[0-9a-f-]{36}$/.test(source)) {
    const item=(await managedSources()).find(row=>`src-${row.id}`===source);
    if(!item) return json({error:"Source inconnue"},400);
    const stream=await streamManaged(item,id,title,artist).catch(()=>null);
    return stream ? json(stream) : json({error:"Flux indisponible"},404);
  }
  const item = (await addonBases()).find((row) => row.tag === source);
  if (!item) return json({ error: "Source inconnue" }, 400);
  const stream = await streamAddon(source, item.base, id, title, artist);
  if (!stream) return json({ error: "Flux indisponible" }, 404);
  return json(stream);
}

async function canvasRoute(url: URL): Promise<Response> {
  const spotifyId = (url.searchParams.get("spotifyId") ?? "").trim();
  const title = (url.searchParams.get("title") ?? "").trim();
  const artist = (url.searchParams.get("artist") ?? "").trim();
  if (!spotifyId && (!title || !artist)) return json({ error: "Titre ou artiste manquant" }, 400);
  const canvas = await resolveCanvas(title, artist, spotifyId);
  if (!canvas) return json({ error: "Canvas indisponible" }, 404);
  return json(canvas);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const url = new URL(req.url);
  const sub = (url.pathname.split("/ketvoice-api")[1] || "/").replace(/\/+$/, "") || "/";
  try {
    if (sub === "/health") return await health();
    if (sub === "/source.json") return sourceManifest();
    if (sub === "/search") return await searchRoute(url);
    if (sub === "/stream") return await streamRoute(url);
    if (sub === "/canvas") return await canvasRoute(url);
    return json({ error: "Not found" }, 404);
  } catch (error) {
    console.error("ketvoice-api", error instanceof Error ? error.name : "unknown");
    return json({ error: "Erreur interne Ketvoice API" }, 500);
  }
});
import {manifest,remoteJson,fill,publicUrl, type Source} from "../_shared/managed_sources.ts";
