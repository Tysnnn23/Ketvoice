// Ketvoice catalogue — Supabase Edge Function (6.4, for the web player at ketvoice-web.vercel.app/player).
//
// A read-only YouTube Music catalogue the browser cannot call itself (no CORS), with the same InnerTube client and
// context as the Android app (YouTubeApi.request, never a cookie). Kept apart from `ketvoice-api` so that nothing here
// can affect the app's search, streaming or Canvas.
//
// Routes (GET only, rate limited per IP, answers cached):
//   GET  /catalog/search?q=...        (an empty q answers {catalog:true}: the web player's probe)
//   GET  /catalog/artist?id=UC...
//   GET  /catalog/album?id=MPRE...
//   GET  /catalog/radio?videoId=...
//
// This file contains no credentials. The code between « >>> catalog mapping » and « <<< catalog mapping » is the same
// as in ketvoice-api/index.ts, where Ketvoice-web's tests read it.

type Json = Record<string, unknown>;

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};

function json(data: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...CORS,
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...extra,
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

// ------------------------------------------------------------------ rate limit
// First layer: per-isolate memory. Second layer: Postgres rolling window so
// the limit survives across isolate restarts. A Postgres outage fails open.

const ROUTE_LIMITS: Record<string, { limit: number; windowS: number }> = {
  "/search": { limit: 60, windowS: 60 },
  "/stream": { limit: 120, windowS: 60 },
  "/canvas": { limit: 30, windowS: 60 },
  // The app beats every 25 s; a pause/resume burst stays well below this.
  "/listeners/heartbeat": { limit: 12, windowS: 60 },
  // The web player's catalogue pages: one call per page opened or search typed.
  "/catalog/search": { limit: 40, windowS: 60 },
  "/catalog/artist": { limit: 30, windowS: 60 },
  "/catalog/album": { limit: 30, windowS: 60 },
  "/catalog/radio": { limit: 20, windowS: 60 },
};

const memoryHits = new Map<string, number[]>();

function clientIp(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const first = forwarded.split(",")[0]?.trim();
  if (first) return first.slice(0, 64);
  return (req.headers.get("x-real-ip") ?? "unknown").slice(0, 64);
}

function memoryTake(key: string, limit: number, windowMs: number): boolean {
  const now = Date.now();
  const hits = (memoryHits.get(key) ?? []).filter((at) => now - at < windowMs);
  if (hits.length >= limit) {
    memoryHits.set(key, hits);
    return false;
  }
  hits.push(now);
  memoryHits.set(key, hits);
  if (memoryHits.size > 5_000) {
    for (const [k, list] of memoryHits) {
      if (list.every((at) => now - at >= windowMs)) memoryHits.delete(k);
    }
  }
  return true;
}

async function postgresTake(
  ip: string,
  route: string,
  limit: number,
  windowS: number,
): Promise<boolean | null> {
  const base = supabaseUrl();
  const key = serviceRole();
  if (!base || !key) return null;
  try {
    const res = await request(
      `${base}/rest/v1/rpc/ketvoice_api_take`,
      {
        method: "POST",
        headers: {
          apikey: key,
          Authorization: `Bearer ${key}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ p_ip: ip, p_route: route, p_limit: limit, p_window_s: windowS }),
      },
      3_000,
    );
    if (!res.ok) return null;
    return (await res.json()) === true;
  } catch {
    return null;
  }
}

async function allow(req: Request, route: string): Promise<Response | null> {
  const config = ROUTE_LIMITS[route];
  if (!config) return null;
  const ip = clientIp(req);
  if (!memoryTake(`${route}|${ip}`, config.limit, config.windowS * 1_000)) {
    return json({ error: "Trop de requêtes" }, 429);
  }
  const dbAllowed = await postgresTake(ip, route, config.limit, config.windowS);
  if (dbAllowed === false) return json({ error: "Trop de requêtes" }, 429);
  return null;
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

// ------------------------------------------------------------------ catalogue
// The app reads YouTube Music's InnerTube API from the phone (YouTubeApi.request,
// YouTubeCatalog.kt, OfficialMusicParser.kt). A browser cannot (no CORS), so the
// web player asks here: four fixed routes, validated ids, the app's own client
// and context, no cookie and no account, answers mapped to compact JSON and kept
// in the search cache table. Nothing else is ever forwarded.

// >>> catalog mapping — pure (no network, no Deno): Ketvoice-web's
// tests/catalog-mapping.test.cjs strips the types of this part and runs it.

type YtTrack = {
  id: string; videoId: string; title: string; artist: string; album: string; durationMs: number;
  coverUrl?: string; albumId?: string; artistId?: string; explicit?: boolean; youtubeCatalogVerified: boolean;
};
type YtArtist = { id: string; name: string; photo?: string };
type YtAlbum = { id: string; title: string; artist: string; artistId?: string; type: string; year?: string; cover?: string };

const YT_VIDEO = /^[A-Za-z0-9_-]{11}$/;
const YT_CHANNEL = /^UC[A-Za-z0-9_-]{10,40}$/;
const YT_ALBUM = /^MPRE[A-Za-z0-9_-]{5,80}$/;
const YT_SONGS_FILTER = "EgWKAQIIAWoKEAkQChAFEAMQBA%3D%3D"; // OfficialMusicParser.SONGS_FILTER
const YT_ARTISTS_FILTER = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"; // YouTubeCatalog.searchArtists
const YT_DURATION = /^\d+:\d{2}(?::\d{2})?$/;
const YT_YEAR = /\b(?:19|20)\d{2}\b/;

function ytObj(value: unknown): Json | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Json : null;
}

function ytAt(value: unknown, ...keys: string[]): unknown {
  let current = value;
  for (const key of keys) {
    const obj = ytObj(current);
    if (!obj) return undefined;
    current = obj[key];
  }
  return current;
}

function ytList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function ytStr(value: unknown): string {
  return typeof value === "string" ? value : "";
}

/** YouTubeJson.objects: every object stored under [key], depth first. */
function ytObjects(node: unknown, key: string, out: Json[] = [], depth = 0): Json[] {
  if (depth > 80 || out.length >= 3_000) return out;
  if (Array.isArray(node)) {
    for (const item of node) ytObjects(item, key, out, depth + 1);
    return out;
  }
  const obj = ytObj(node);
  if (!obj) return out;
  for (const k of Object.keys(obj)) {
    const value = obj[k];
    if (k === key && ytObj(value)) out.push(value as Json);
    ytObjects(value, key, out, depth + 1);
  }
  return out;
}

/** YouTubeJson.text: simpleText, or the runs put together. */
function ytText(value: unknown): string {
  const obj = ytObj(value);
  if (!obj) return "";
  if (typeof obj.simpleText === "string") return obj.simpleText;
  return ytList(obj.runs).map((run) => ytStr(ytAt(run, "text"))).join("");
}

/** YouTubeJson.image: the widest https thumbnail under [value]. */
function ytImage(value: unknown): string | undefined {
  let best: string | undefined;
  let width = -1;
  const visit = (node: unknown, depth: number): void => {
    if (depth > 40) return;
    if (Array.isArray(node)) {
      node.forEach((item) => visit(item, depth + 1));
      return;
    }
    const obj = ytObj(node);
    if (!obj) return;
    const url = ytStr(obj.url);
    const w = typeof obj.width === "number" ? obj.width : -1;
    if (url.startsWith("https://") && w > 0 && w >= width) {
      best = url;
      width = w;
    }
    Object.values(obj).forEach((item) => visit(item, depth + 1));
  };
  visit(value, 0);
  return best;
}

function ytPageType(browse: unknown): string {
  return ytStr(ytAt(browse, "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType"));
}

function ytVideoType(watch: unknown): string {
  return ytStr(ytAt(watch, "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"));
}

function ytSeconds(text: string): number {
  return text.split(":").reduce((value, part) => value * 60 + Number(part), 0) * 1_000;
}

/** The list rows of a response, without looking inside a row (YouTubeCatalog.parseRows' visit). */
function ytListRows(raw: unknown): Json[] {
  const rows: Json[] = [];
  const visit = (node: unknown, depth: number): void => {
    if (depth > 80 || rows.length >= 500) return;
    if (Array.isArray(node)) {
      node.forEach((item) => visit(item, depth + 1));
      return;
    }
    const obj = ytObj(node);
    if (!obj) return;
    for (const key of Object.keys(obj)) {
      if (key === "musicResponsiveListItemRenderer") {
        const row = ytObj(obj[key]);
        if (row) rows.push(row);
      } else visit(obj[key], depth + 1);
    }
  };
  visit(raw, 0);
  return rows;
}

function ytFlexRuns(row: Json, index: number): unknown[] {
  return ytList(ytAt(ytList(row.flexColumns)[index], "musicResponsiveListItemFlexColumnRenderer", "text", "runs"));
}

function ytFixedRuns(row: Json): unknown[] {
  return ytList(row.fixedColumns).flatMap((column) =>
    ytList(ytAt(column, "musicResponsiveListItemFixedColumnRenderer", "text", "runs"))
  );
}

function ytRowWatch(row: Json, titleRuns: unknown[]): Json | null {
  return ytObj(ytAt(titleRuns[0], "navigationEndpoint", "watchEndpoint")) ??
    ytObj(ytAt(row, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
      "playNavigationEndpoint", "watchEndpoint")) ??
    ytObj(ytAt(row, "navigationEndpoint", "watchEndpoint"));
}

function ytExplicit(row: Json): boolean {
  return JSON.stringify(row.badges ?? []).includes("MUSIC_EXPLICIT_BADGE");
}

function ytTrack(t: Omit<YtTrack, "id" | "youtubeCatalogVerified">): YtTrack {
  const out: YtTrack = {
    id: `yt:${t.videoId}`, videoId: t.videoId, title: t.title.trim().slice(0, 300),
    artist: t.artist.slice(0, 300), album: t.album.slice(0, 300), durationMs: Math.max(0, Math.round(t.durationMs)),
    youtubeCatalogVerified: true,
  };
  if (t.coverUrl) out.coverUrl = t.coverUrl;
  if (t.albumId && YT_ALBUM.test(t.albumId)) out.albumId = t.albumId;
  if (t.artistId && YT_CHANNEL.test(t.artistId)) out.artistId = t.artistId;
  if (t.explicit) out.explicit = true;
  return out;
}

const YT_VARIANTS = ["slowed", "sped up", "speed up", "nightcore", "8d", "bass boosted", "reverb", "karaoke"];

/** YouTubeCatalog.parseRows: list rows as titles (artist and album pages). */
function ytRows(
  raw: unknown,
  o: { officialOnly: boolean; album?: string; albumId?: string; artist?: string; artistId?: string },
): YtTrack[] {
  const out: YtTrack[] = [];
  const seen = new Set<string>();
  for (const row of ytListRows(raw)) {
    if (!Array.isArray(row.flexColumns)) continue;
    const titleRuns = ytFlexRuns(row, 0);
    const title = titleRuns.map((run) => ytStr(ytAt(run, "text"))).join("").trim();
    if (!title) continue;
    const watch = ytRowWatch(row, titleRuns);
    const video = ytStr(watch?.videoId);
    const type = ytVideoType(watch);
    if (!YT_VIDEO.test(video) || seen.has(video)) continue;
    if (o.officialOnly && type !== "MUSIC_VIDEO_TYPE_ATV" && type !== "MUSIC_VIDEO_TYPE_OMV") continue;
    const declared = ytStr(ytAt(row, "playlistItemData", "videoId"));
    if (declared && declared !== video) continue;
    const lower = title.toLowerCase();
    if ([...YT_VARIANTS, "type beat", "instrumental"].some((v) => lower.includes(v))) continue;
    const artists: string[] = [];
    let album = "", albumId = "", artistId = "", durationMs = 0;
    const scan = (runs: unknown[]) => {
      for (const run of runs) {
        const text = ytStr(ytAt(run, "text")).trim();
        const browse = ytAt(run, "navigationEndpoint", "browseEndpoint");
        const page = ytPageType(browse);
        if (page === "MUSIC_PAGE_TYPE_ARTIST") {
          if (text && text.toLowerCase() !== "artiste" && !artists.includes(text)) artists.push(text);
          if (!artistId) artistId = ytStr(ytAt(browse, "browseId"));
        }
        if (page === "MUSIC_PAGE_TYPE_ALBUM" && text) {
          album = text;
          albumId = ytStr(ytAt(browse, "browseId"));
        }
        if (!durationMs && YT_DURATION.test(text)) durationMs = ytSeconds(text);
      }
    };
    for (let i = 1; i < row.flexColumns.length; i++) scan(ytFlexRuns(row, i));
    scan(ytFixedRuns(row));
    if (!artists.length && o.artist) {
      artists.push(o.artist);
      artistId = o.artistId ?? "";
    }
    seen.add(video);
    out.push(ytTrack({
      videoId: video, title, artist: artists.join(", "), album: album || o.album || "", durationMs,
      coverUrl: ytImage(row.thumbnail), albumId: albumId || o.albumId, artistId, explicit: ytExplicit(row),
    }));
    if (out.length >= 50) break;
  }
  return out;
}

/** OfficialMusicParser.parse: songs of a search (album versions only, with their artist and album). */
function ytSongs(raw: unknown, query = ""): YtTrack[] {
  const out: YtTrack[] = [];
  const seen = new Set<string>();
  const q = query.toLowerCase();
  for (const row of ytListRows(raw)) {
    if (!Array.isArray(row.flexColumns)) continue;
    const titleRuns = ytFlexRuns(row, 0);
    const watch = ytObj(ytAt(titleRuns[0], "navigationEndpoint", "watchEndpoint")) ??
      ytObj(ytAt(row, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
        "playNavigationEndpoint", "watchEndpoint"));
    if (!watch || ytVideoType(watch) !== "MUSIC_VIDEO_TYPE_ATV") continue;
    const video = ytStr(watch.videoId);
    const declared = ytStr(ytAt(row, "playlistItemData", "videoId"));
    if (!YT_VIDEO.test(video) || (declared && declared !== video) || seen.has(video)) continue;
    const title = titleRuns.map((run) => ytStr(ytAt(run, "text"))).join("").trim();
    const lower = title.toLowerCase();
    if (YT_VARIANTS.some((v) => lower.includes(v) && !q.includes(v))) continue;
    const meta: unknown[] = [];
    for (let i = 1; i < row.flexColumns.length; i++) meta.push(...ytFlexRuns(row, i));
    meta.push(...ytFixedRuns(row));
    const artists: string[] = [];
    let album = "", albumId = "", artistId = "", durationMs = 0;
    for (const run of meta) {
      const name = ytStr(ytAt(run, "text")).trim();
      const browse = ytAt(run, "navigationEndpoint", "browseEndpoint");
      const page = ytPageType(browse);
      if (page === "MUSIC_PAGE_TYPE_ARTIST") {
        if (name && !artists.includes(name)) artists.push(name);
        if (!artistId) artistId = ytStr(ytAt(browse, "browseId"));
      }
      if (page === "MUSIC_PAGE_TYPE_ALBUM") {
        album = name;
        albumId = ytStr(ytAt(browse, "browseId"));
      }
      const time = name.split(/\s*[•·]\s*/).find((part) => YT_DURATION.test(part));
      if (time) durationMs = ytSeconds(time);
    }
    if (!artists.length || !albumId) {
      // The row's menu still names the artist and the album (Go to artist / Go to album).
      const actions = ytObjects(row.menu, "menuNavigationItemRenderer");
      const actionId = (icon: string, page: string): string => {
        for (const action of actions) {
          const browse = ytAt(action, "navigationEndpoint", "browseEndpoint");
          if (ytStr(ytAt(action, "icon", "iconType")) === icon && ytPageType(browse) === page) {
            const id = ytStr(ytAt(browse, "browseId"));
            if (id) return id;
          }
        }
        return "";
      };
      const segments = ytFlexRuns(row, 1).map((run) => ytStr(ytAt(run, "text"))).join("")
        .split(/\s*[•·]\s*/).map((s) => s.trim());
      if (!artists.length) {
        const id = actionId("ARTIST", "MUSIC_PAGE_TYPE_ARTIST");
        if (id.startsWith("UC") && segments[0]) {
          artistId = id;
          artists.push(segments[0]);
        }
      }
      if (!albumId) {
        const id = actionId("ALBUM", "MUSIC_PAGE_TYPE_ALBUM");
        if (id.startsWith("MPRE") && segments[1]) {
          albumId = id;
          album = segments[1];
        }
      }
    }
    const catalogueText = `${title} ${album}`.toLowerCase();
    if (["type beat", "instrumental", "reprod", "prod. by", "made by"].some((v) => catalogueText.includes(v))) continue;
    if (!title || !artists.length || !albumId.startsWith("MPRE")) continue;
    seen.add(video);
    out.push(ytTrack({
      videoId: video, title, artist: artists.join(", "), album, durationMs, coverUrl: ytImage(row.thumbnail),
      albumId, artistId, explicit: ytExplicit(row),
    }));
    if (out.length >= 30) break;
  }
  return out;
}

/** YouTubeCatalog.searchArtists' rows, plus the top result card when it is an artist. */
function ytSearchArtists(raw: unknown): YtArtist[] {
  const out: YtArtist[] = [];
  const add = (id: string, name: string, photo: string | undefined) => {
    if (!YT_CHANNEL.test(id) || !name.trim() || out.some((a) => a.id === id)) return;
    const artist: YtArtist = { id, name: name.trim().slice(0, 200) };
    if (photo) artist.photo = photo;
    out.push(artist);
  };
  for (const card of ytObjects(raw, "musicCardShelfRenderer")) {
    const browse = ytAt(ytList(ytAt(card, "title", "runs"))[0], "navigationEndpoint", "browseEndpoint");
    if (ytPageType(browse) === "MUSIC_PAGE_TYPE_ARTIST") {
      add(ytStr(ytAt(browse, "browseId")), ytText(card.title), ytImage(card.thumbnail));
    }
  }
  for (const row of ytListRows(raw)) {
    const browse = ytAt(row, "navigationEndpoint", "browseEndpoint") ??
      ytAt(ytFlexRuns(row, 0)[0], "navigationEndpoint", "browseEndpoint");
    if (ytPageType(browse) !== "MUSIC_PAGE_TYPE_ARTIST") continue;
    add(ytStr(ytAt(browse, "browseId")), ytText(ytAt(ytList(row.flexColumns)[0], "musicResponsiveListItemFlexColumnRenderer", "text")),
      ytImage(row.thumbnail));
  }
  return out.slice(0, 8);
}

/** Single, EP or Album: the item's own subtitle first, then its section (« Singles et EP »). */
function ytReleaseType(subtitle: string, section: string): string {
  for (const text of [subtitle, section]) {
    if (/single/i.test(text)) return "Single";
    if (/\bEPs?\b/.test(text)) return "EP";
  }
  return "Album";
}

/** Albums among a search's rows (MUSIC_PAGE_TYPE_ALBUM: « Album • Artist • 2001 »). */
function ytSearchAlbums(raw: unknown): YtAlbum[] {
  const out: YtAlbum[] = [];
  for (const row of ytListRows(raw)) {
    const browse = ytAt(row, "navigationEndpoint", "browseEndpoint");
    const id = ytStr(ytAt(browse, "browseId"));
    if (ytPageType(browse) !== "MUSIC_PAGE_TYPE_ALBUM" || !YT_ALBUM.test(id) || out.some((a) => a.id === id)) continue;
    const title = ytFlexRuns(row, 0).map((run) => ytStr(ytAt(run, "text"))).join("").trim();
    if (!title) continue;
    const runs = ytFlexRuns(row, 1);
    const subtitle = runs.map((run) => ytStr(ytAt(run, "text"))).join("");
    const artists: string[] = [];
    let artistId = "";
    for (const run of runs) {
      const b = ytAt(run, "navigationEndpoint", "browseEndpoint");
      if (ytPageType(b) === "MUSIC_PAGE_TYPE_ARTIST") {
        artists.push(ytStr(ytAt(run, "text")).trim());
        if (!artistId) artistId = ytStr(ytAt(b, "browseId"));
      }
    }
    const album: YtAlbum = {
      id, title: title.slice(0, 300), artist: artists.filter(Boolean).join(", "),
      type: ytReleaseType(subtitle.split(/\s*[•·]\s*/)[0] ?? "", ""),
    };
    if (YT_CHANNEL.test(artistId)) album.artistId = artistId;
    const year = YT_YEAR.exec(subtitle)?.[0];
    if (year) album.year = year;
    const cover = ytImage(row.thumbnail);
    if (cover) album.cover = cover;
    out.push(album);
    if (out.length >= 12) break;
  }
  return out;
}

/** The albums the songs of a search belong to, when the search itself listed none. */
function ytAlbumsOfSongs(tracks: YtTrack[]): YtAlbum[] {
  const out: YtAlbum[] = [];
  for (const t of tracks) {
    if (!t.albumId || !t.album || out.some((a) => a.id === t.albumId)) continue;
    const album: YtAlbum = { id: t.albumId, title: t.album, artist: t.artist, type: "Album" };
    if (t.artistId) album.artistId = t.artistId;
    if (t.coverUrl) album.cover = t.coverUrl;
    out.push(album);
    if (out.length >= 8) break;
  }
  return out;
}

/** Music videos without album (radio, artist carousels): « Artist - Title (Official Video) » becomes « Title ». */
function ytCleanVideoTitle(title: string, artist: string): string {
  let clean = title.trim();
  const main = artist.split(",")[0]?.trim() ?? "";
  if (main && clean.toLowerCase().startsWith(`${main.toLowerCase()} - `)) clean = clean.slice(main.length + 3);
  clean = clean.replace(/\s*[([](?:[^)\]]*\b(?:official|officiel(?:le)?|video|vidéo|audio|lyrics?|paroles|clip|visuali[sz]er|mv|hd|4k)\b[^)\]]*)[)\]]\s*/gi, " ")
    .replace(/\s+/g, " ").trim();
  return clean || title.trim();
}

/** YouTubeCatalog.artistHeaderArtwork: a portrait photo first, then the widest, then the background. */
function ytHeaderPhoto(header: Json): string | undefined {
  const images = (field: string): Json[] => {
    const source = ytObj(header[field]);
    if (!source) return [];
    return [...ytObjects(source, "thumbnail"), source].flatMap((node) =>
      ytList(node.thumbnails).map(ytObj).filter((x): x is Json => !!x)
    ).filter((img) =>
      ytStr(img.url).startsWith("https://") && Number(img.width) > 0 && Number(img.height) > 0
    );
  };
  const widest = (list: Json[]): Json | undefined =>
    list.reduce<Json | undefined>((best, img) => !best || Number(img.width) > Number(best.width) ? img : best, undefined);
  const front = [...images("foregroundThumbnail"), ...images("thumbnail")];
  const portrait = widest(front.filter((img) => Number(img.width) >= 400 && Number(img.height) / Number(img.width) >= 0.85));
  const chosen = portrait ?? widest(front) ?? widest(images("backgroundThumbnail"));
  return chosen ? ytStr(chosen.url) : undefined;
}

/** A video of a carousel (musicTwoRowItemRenderer with a watch endpoint) as a title. */
function ytTwoRowTrack(item: Json, fallbackArtist: string, fallbackId: string): YtTrack | null {
  const watch = ytObj(ytAt(item, "navigationEndpoint", "watchEndpoint"));
  const video = ytStr(watch?.videoId);
  const type = ytVideoType(watch);
  if (!YT_VIDEO.test(video) || (type !== "MUSIC_VIDEO_TYPE_ATV" && type !== "MUSIC_VIDEO_TYPE_OMV")) return null;
  const artists: string[] = [];
  let artistId = "";
  for (const run of ytList(ytAt(item, "subtitle", "runs"))) {
    const browse = ytAt(run, "navigationEndpoint", "browseEndpoint");
    if (ytPageType(browse) === "MUSIC_PAGE_TYPE_ARTIST") {
      artists.push(ytStr(ytAt(run, "text")).trim());
      if (!artistId) artistId = ytStr(ytAt(browse, "browseId"));
    }
  }
  const artist = artists.filter(Boolean).join(", ") || fallbackArtist;
  const raw = ytText(item.title);
  if (!raw.trim()) return null;
  return ytTrack({
    videoId: video, title: type === "MUSIC_VIDEO_TYPE_ATV" ? raw : ytCleanVideoTitle(raw, artist), artist, album: "",
    durationMs: 0, coverUrl: ytImage(item.thumbnailRenderer), artistId: artistId || fallbackId,
  });
}

/** YouTubeCatalog.artist: name, photo, listeners, about, titles, releases and close artists. */
function ytArtistPage(raw: unknown, id: string) {
  const header = ytObjects(raw, "musicImmersiveHeaderRenderer")[0] ??
    ytObjects(raw, "musicVisualHeaderRenderer")[0] ?? ytObjects(raw, "musicResponsiveHeaderRenderer")[0];
  if (!header) return null;
  const name = ytText(header.title).trim();
  if (!name) return null;
  const about = ytObjects(raw, "musicDescriptionShelfRenderer")[0];
  const listeners = ytText(header.monthlyListenerCount) ||
    ytText(ytObjects(raw, "subscribeButtonRenderer")[0]?.subscriberCountText) ||
    ytText(about?.subheader);
  const releases: YtAlbum[] = [];
  const related: YtArtist[] = [];
  const videos: YtTrack[] = [];
  for (const shelf of ytObjects(raw, "musicCarouselShelfRenderer")) {
    const section = ytText(ytAt(shelf, "header", "musicCarouselShelfBasicHeaderRenderer", "title"));
    for (const item of ytObjects(shelf.contents, "musicTwoRowItemRenderer")) {
      const browse = ytAt(item, "navigationEndpoint", "browseEndpoint");
      const browseId = ytStr(ytAt(browse, "browseId"));
      const title = ytText(item.title).trim();
      const picture = ytImage(item.thumbnailRenderer);
      const subtitle = ytText(item.subtitle);
      const page = ytPageType(browse);
      if (page === "MUSIC_PAGE_TYPE_ALBUM" && YT_ALBUM.test(browseId) && title && !releases.some((r) => r.id === browseId)) {
        const release: YtAlbum = { id: browseId, title: title.slice(0, 300), artist: name, artistId: id, type: ytReleaseType(subtitle, section) };
        const year = YT_YEAR.exec(subtitle)?.[0];
        if (year) release.year = year;
        if (picture) release.cover = picture;
        releases.push(release);
      } else if (page === "MUSIC_PAGE_TYPE_ARTIST" && YT_CHANNEL.test(browseId) && title && browseId !== id &&
        !related.some((a) => a.id === browseId)) {
        const artist: YtArtist = { id: browseId, name: title.slice(0, 200) };
        if (picture) artist.photo = picture;
        related.push(artist);
      } else if (!browse) {
        const track = ytTwoRowTrack(item, name, id);
        if (track && !videos.some((v) => v.videoId === track.videoId)) videos.push(track);
      }
    }
  }
  // The titles of the page are not filtered on ATV/OMV (YouTubeCatalog.artist); some pages only carry videos.
  let tracks = ytRows(raw, { officialOnly: false }).slice(0, 20);
  if (!tracks.length) tracks = videos.slice(0, 20);
  const photo = ytHeaderPhoto(header);
  return {
    artist: { id, name: name.slice(0, 200), ...(photo ? { photo } : {}), listeners: listeners.slice(0, 80) },
    description: ytText(about?.description).slice(0, 1_200),
    tracks,
    albums: releases.filter((r) => r.type === "Album").slice(0, 30),
    singles: releases.filter((r) => r.type !== "Album").slice(0, 30),
    related: related.slice(0, 12),
  };
}

/** YouTubeCatalog.album: title, artist, cover, year and the album's own titles. */
function ytAlbumPage(raw: unknown, id: string) {
  const header = ytObjects(raw, "musicResponsiveHeaderRenderer")[0] ?? ytObjects(raw, "musicDetailHeaderRenderer")[0];
  if (!header) return null;
  const title = ytText(header.title).trim();
  if (!title) return null;
  const artistLink = ytObjects(raw, "browseEndpoint").find((b) => ytPageType(b) === "MUSIC_PAGE_TYPE_ARTIST");
  const artistId = ytStr(artistLink?.browseId);
  const artist = (ytText(header.straplineTextOne) || ytText(header.subtitle)).trim();
  const cover = ytImage(header.thumbnail);
  const rows = ytRows(raw, { officialOnly: true, album: title, albumId: id, artist, artistId });
  if (!rows.length) return null;
  const subtitle = ytText(header.subtitle);
  const year = YT_YEAR.exec(subtitle)?.[0];
  return {
    album: {
      id, title: title.slice(0, 300), artist: artist.slice(0, 300), ...(YT_CHANNEL.test(artistId) ? { artistId } : {}),
      type: ytReleaseType(subtitle.split(/\s*[•·]\s*/)[0] ?? "", ""), ...(year ? { year } : {}), ...(cover ? { cover } : {}),
    },
    tracks: rows.map((t) => t.coverUrl || !cover ? t : { ...t, coverUrl: cover }),
  };
}

/** The « next » panel of a watch playlist RDAMVM<id> (YouTube Music's radio of a title). */
function ytRadio(raw: unknown, seed: string): YtTrack[] {
  const out: YtTrack[] = [];
  for (const item of ytObjects(raw, "playlistPanelVideoRenderer")) {
    const video = ytStr(item.videoId) || ytStr(ytAt(item, "navigationEndpoint", "watchEndpoint", "videoId"));
    if (!YT_VIDEO.test(video) || out.some((t) => t.videoId === video)) continue;
    const type = ytVideoType(ytAt(item, "navigationEndpoint", "watchEndpoint"));
    if (type === "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") continue;
    const name = ytText(item.title).trim();
    if (!name) continue;
    const artists: string[] = [];
    let artistId = "", album = "", albumId = "";
    for (const run of ytList(ytAt(item, "longBylineText", "runs"))) {
      const text = ytStr(ytAt(run, "text")).trim();
      const browse = ytAt(run, "navigationEndpoint", "browseEndpoint");
      const page = ytPageType(browse);
      if ((page === "MUSIC_PAGE_TYPE_ARTIST" || page === "MUSIC_PAGE_TYPE_USER_CHANNEL") && text) {
        artists.push(text);
        if (!artistId && page === "MUSIC_PAGE_TYPE_ARTIST") artistId = ytStr(ytAt(browse, "browseId"));
      }
      if (page === "MUSIC_PAGE_TYPE_ALBUM" && text) {
        album = text;
        albumId = ytStr(ytAt(browse, "browseId"));
      }
    }
    const artist = artists.join(", ") || ytText(item.shortBylineText).trim();
    const length = ytText(item.lengthText).trim();
    out.push(ytTrack({
      videoId: video, title: type === "MUSIC_VIDEO_TYPE_ATV" ? name : ytCleanVideoTitle(name, artist), artist, album,
      durationMs: YT_DURATION.test(length) ? ytSeconds(length) : 0, coverUrl: ytImage(item.thumbnail), albumId, artistId,
    }));
    if (out.length >= 50) break;
  }
  // The title the radio started from comes first, as YouTube Music lists it.
  out.sort((a, b) => (a.videoId === seed ? -1 : 0) - (b.videoId === seed ? -1 : 0));
  return out;
}

/** A search query as the catalogue takes it: no control characters, spaces collapsed, 2 to 100 characters. */
function ytQuery(value: string | null): string | null {
  const clean = (value ?? "").replace(/[\u0000-\u001f\u007f-\u009f]/g, " ").replace(/\s+/g, " ").trim();
  return clean.length >= 2 && clean.length <= 100 ? clean : null;
}

// <<< catalog mapping

const INNERTUBE_CLIENT = { clientName: "WEB_REMIX", clientVersion: "1.20241014.01.00", hl: "fr", gl: "FR" };
const INNERTUBE_MAX_BYTES = 6_000_000;
const CATALOG_TTL_S: Record<string, number> = { s: 6 * 3_600, a: 12 * 3_600, l: 24 * 3_600, r: 3_600 };
const catalogMemory = new Map<string, { at: number; data: Json }>();

/** One InnerTube call with the app's client (YouTubeApi.request, authenticated = false): 8 s, 6 MB at most. */
async function innertube(endpoint: "search" | "browse" | "next", fields: Json): Promise<Json | null> {
  try {
    const res = await request(`https://music.youtube.com/youtubei/v1/${endpoint}?prettyPrint=false`, {
      method: "POST",
      redirect: "manual",
      headers: { "User-Agent": "Mozilla/5.0", "Content-Type": "application/json", Origin: "https://music.youtube.com" },
      body: JSON.stringify({ ...fields, context: { client: INNERTUBE_CLIENT } }),
    }, 8_000);
    if (!res.ok || !res.body) {
      await res.body?.cancel();
      return null;
    }
    if (Number(res.headers.get("content-length") ?? 0) > INNERTUBE_MAX_BYTES) {
      await res.body.cancel();
      return null;
    }
    const reader = res.body.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > INNERTUBE_MAX_BYTES) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(size);
    let at = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, at);
      at += chunk.byteLength;
    }
    return ytObj(JSON.parse(new TextDecoder().decode(bytes)));
  } catch {
    return null;
  }
}

// Cache keys start with « C| »: /search keys are lowercased, so the two never meet in the table.
async function catalogCached(key: string, ttlS: number): Promise<Json | null> {
  const memory = catalogMemory.get(key);
  if (memory && Date.now() - memory.at < ttlS * 1_000) return memory.data;
  const base = supabaseUrl();
  const role = serviceRole();
  if (!base || !role) return null;
  try {
    const res = await request(`${base}/rest/v1/rpc/ketvoice_search_cache_get`, {
      method: "POST",
      headers: { apikey: role, Authorization: `Bearer ${role}`, "Content-Type": "application/json" },
      body: JSON.stringify({ p_query: key, p_max_age_s: ttlS }),
    }, 3_000);
    if (!res.ok) return null;
    const data = ytObj(await res.json());
    if (data) catalogRemember(key, data);
    return data;
  } catch {
    return null;
  }
}

function catalogRemember(key: string, data: Json): void {
  if (catalogMemory.size >= 300) {
    const oldest = catalogMemory.keys().next().value;
    if (oldest !== undefined) catalogMemory.delete(oldest);
  }
  catalogMemory.set(key, { at: Date.now(), data });
}

async function catalogStore(key: string, data: Json): Promise<void> {
  catalogRemember(key, data);
  const base = supabaseUrl();
  const role = serviceRole();
  if (!base || !role) return;
  try {
    await request(`${base}/rest/v1/rpc/ketvoice_search_cache_put`, {
      method: "POST",
      headers: { apikey: role, Authorization: `Bearer ${role}`, "Content-Type": "application/json" },
      body: JSON.stringify({ p_query: key, p_results: data }),
    }, 3_000);
  } catch {
    // The cache is an optimisation, never a dependency.
  }
}

function catalogAnswer(data: Json, cached: boolean): Response {
  return json({ ...data, cached, catalog: true }, 200, { "cache-control": "public, max-age=600" });
}

async function catalogSearch(q: string): Promise<Json | null> {
  const [songsRaw, artistsRaw] = await Promise.all([
    innertube("search", { query: q, params: YT_SONGS_FILTER }),
    innertube("search", { query: q, params: YT_ARTISTS_FILTER }),
  ]);
  let tracks = ytSongs(songsRaw, q);
  let artists = ytSearchArtists(artistsRaw);
  let albums: YtAlbum[] = [];
  let plain: Json | null = null;
  if (!tracks.length || !artists.length) {
    // MusicApi.searchYtMusic and YouTubeCatalog.searchArtists retry without a filter.
    plain = await innertube("search", { query: q });
    if (!tracks.length) tracks = ytSongs(plain, q);
    if (!tracks.length) tracks = ytRows(plain, { officialOnly: true }).slice(0, 20);
    if (!artists.length) artists = ytSearchArtists(plain);
    albums = ytSearchAlbums(plain);
  }
  if (!songsRaw && !artistsRaw && !plain) return null;
  if (!albums.length) albums = ytAlbumsOfSongs(tracks);
  return { query: q, tracks, artists, albums };
}

async function catalogRoute(sub: string, url: URL): Promise<Response> {
  let key = "", kind = "";
  let load: () => Promise<Json | null>;
  if (sub === "/catalog/search") {
    // Like /search, a query under two characters is an empty answer, not an error: the web player asks
    // /catalog/search?q= to learn whether these routes are deployed, without a call to YouTube.
    if ((url.searchParams.get("q") ?? "").trim().length < 2) {
      return json({ query: "", tracks: [], artists: [], albums: [], catalog: true });
    }
    const q = ytQuery(url.searchParams.get("q"));
    if (!q) return json({ error: "Recherche trop longue", catalog: true }, 400);
    kind = "s";
    key = `C|s|${q.toLowerCase()}`;
    load = () => catalogSearch(q);
  } else if (sub === "/catalog/artist") {
    const id = (url.searchParams.get("id") ?? "").trim();
    if (!YT_CHANNEL.test(id)) return json({ error: "Artiste invalide", catalog: true }, 400);
    kind = "a";
    key = `C|a|${id}`;
    load = async () => {
      const raw = await innertube("browse", { browseId: id });
      return raw ? (ytArtistPage(raw, id) ?? {}) : null;
    };
  } else if (sub === "/catalog/album") {
    const id = (url.searchParams.get("id") ?? "").trim();
    if (!YT_ALBUM.test(id)) return json({ error: "Album invalide", catalog: true }, 400);
    kind = "l";
    key = `C|l|${id}`;
    load = async () => {
      const raw = await innertube("browse", { browseId: id });
      return raw ? (ytAlbumPage(raw, id) ?? {}) : null;
    };
  } else {
    const id = (url.searchParams.get("videoId") ?? "").trim();
    if (!YT_VIDEO.test(id)) return json({ error: "Titre invalide", catalog: true }, 400);
    kind = "r";
    key = `C|r|${id}`;
    load = async () => {
      const raw = await innertube("next", { videoId: id, playlistId: `RDAMVM${id}`, isAudioOnly: true });
      return raw ? { seed: id, tracks: ytRadio(raw, id) } : null;
    };
  }
  const ttl = CATALOG_TTL_S[kind];
  const cached = await catalogCached(key, ttl);
  if (cached) return catalogAnswer(cached, true);
  const data = await load();
  if (!data) return json({ error: "Catalogue indisponible", catalog: true }, 502);
  const empty = Object.keys(data).length === 0 ||
    (kind === "r" && !ytList(data.tracks).length) ||
    (kind === "s" && !ytList(data.tracks).length && !ytList(data.artists).length && !ytList(data.albums).length);
  if (empty) return json({ error: "Introuvable", catalog: true }, 404);
  await catalogStore(key, data);
  return catalogAnswer(data, false);
}


Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const url = new URL(req.url);
  const sub = (url.pathname.split("/ketvoice-catalog")[1] || "/").replace(/\/+$/, "") || "/";
  try {
    if (sub === "/catalog/search" || sub === "/catalog/artist" || sub === "/catalog/album" || sub === "/catalog/radio") {
      if (req.method !== "GET") return json({ error: "Méthode non autorisée", catalog: true }, 405);
      const limited = await allow(req, sub);
      if (limited) return limited;
      return await catalogRoute(sub, url);
    }
    return json({ error: "Not found" }, 404);
  } catch (error) {
    console.error("ketvoice-catalog", error instanceof Error ? error.name : "unknown");
    return json({ error: "Erreur interne Ketvoice", catalog: true }, 500);
  }
});
