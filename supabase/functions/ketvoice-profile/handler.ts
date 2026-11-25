// ketvoice-profile: a listener's profile photo or banner, served only to whoever may see that profile's identity
// (its owner, a friend, or anyone while the profile is public; the rule is ketvoice_profile_image() in
// 20261125120500_social_public_card.sql). The pictures stay in the private `ketvoice-avatars` bucket: this function
// reads them with the service role and answers the bytes, never a storage path, a signed link or an account id.
// A private, unknown or blocked handle answers the same 404, so handles cannot be probed.
export type Dependencies = {
  /** The account id behind a user's access token, or null (expired, anonymous key, malformed). */
  userOf: (token: string) => Promise<string | null>;
  /** ketvoice_profile_image(viewer, handle, kind) through the service role. */
  imagePath: (viewer: string | null, handle: string, kind: "avatar" | "banner") => Promise<string | null>;
  /** The object's bytes from the private bucket, or null. */
  download: (path: string) => Promise<Uint8Array | null>;
  /** Per-address budget; false refuses the request. */
  quota: (bucket: string) => Promise<boolean>;
  /** The project's public (anon) key: a request carrying it is anonymous. */
  anonKey: () => string;
};

const HANDLE = /^@?[A-Za-z0-9_.]{3,24}$/;
const PATH = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(avatar|banner)-[0-9]{10,16}\.jpg$/;
export const MAX_BYTES = 1024 * 1024;
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, apikey, content-type",
  "access-control-allow-methods": "GET, HEAD, OPTIONS",
};

function reply(body: unknown, status: number, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: {
    ...CORS, "content-type": "application/json", "cache-control": "no-store", "x-content-type-options": "nosniff", ...extra,
  } });
}

/** The bearer token of a signed-in listener, or null for an anonymous request. */
export function bearer(req: Request, anon: string): string | null {
  const raw = req.headers.get("authorization") ?? "";
  const token = raw.toLowerCase().startsWith("bearer ") ? raw.slice(7).trim() : "";
  if (!token || token === anon || token.length > 4096 || !/^[A-Za-z0-9._-]+$/.test(token)) return null;
  return token;
}

/** The client address for the budget (the first hop of the gateway's forwarded list). */
export function clientIp(req: Request): string {
  const forwarded = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
  return /^[0-9a-fA-F:.]{3,45}$/.test(forwarded) ? forwarded : "unknown";
}

export function isJpeg(bytes: Uint8Array): boolean {
  return bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

export function createProfileHandler(deps: Dependencies) {
  return async function handler(req: Request): Promise<Response> {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (req.method !== "GET" && req.method !== "HEAD") return reply({ error: "method" }, 405, { allow: "GET, HEAD, OPTIONS" });
    const url = new URL(req.url);
    const route = url.pathname.replace(/^.*\/ketvoice-profile/, "") || "/";
    if (route === "/health") return reply({ ok: true }, 200);
    if (route !== "/image") return reply({ error: "not_found" }, 404);
    const handle = url.searchParams.get("handle") ?? "";
    const kind = url.searchParams.get("kind") ?? "avatar";
    if (!HANDLE.test(handle) || (kind !== "avatar" && kind !== "banner")) return reply({ error: "bad_request" }, 400);
    if (!(await deps.quota("profile-image:" + clientIp(req)).catch(() => true))) {
      return reply({ error: "busy" }, 429, { "retry-after": "60" });
    }
    const token = bearer(req, deps.anonKey());
    const viewer = token ? await deps.userOf(token).catch(() => null) : null;
    const path = await deps.imagePath(viewer, handle.replace(/^@/, "").toLowerCase(), kind).catch(() => null);
    if (!path || !PATH.test(path)) return reply({ error: "not_found" }, 404);
    const bytes = await deps.download(path).catch(() => null);
    if (!bytes || bytes.length === 0 || bytes.length > MAX_BYTES || !isJpeg(bytes)) return reply({ error: "not_found" }, 404);
    // What a signed-in friend sees is theirs alone; a public picture may be cached by browsers and link previews.
    const cache = viewer ? "private, max-age=300" : "public, max-age=600, s-maxage=600";
    return new Response(req.method === "HEAD" ? null : bytes, { status: 200, headers: {
      ...CORS, "content-type": "image/jpeg", "cache-control": cache, "vary": "authorization",
      "x-content-type-options": "nosniff", "content-security-policy": "default-src 'none'",
    } });
  };
}
