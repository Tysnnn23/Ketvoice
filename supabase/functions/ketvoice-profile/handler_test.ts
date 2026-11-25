import { bearer, clientIp, createProfileHandler, isJpeg } from "./handler.ts";

const assert = (value: unknown, message = "Assertion failed") => { if (!value) throw new Error(message); };
const ANON = "anon.public.key";
const ALICE = "aaaaaaaa-0000-4000-8000-000000000001";
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);

/** A profile world: alice is private (visible to herself and her friend bob), pub is public. */
function fixture(opts: { quota?: boolean; bytes?: Uint8Array } = {}) {
  const asked: Array<[string | null, string, string]> = [];
  const handler = createProfileHandler({
    anonKey: () => ANON,
    userOf: async (token) => token === "bob-token" ? "bob" : token === "alice-token" ? ALICE : null,
    imagePath: async (viewer, handle, kind) => {
      asked.push([viewer, handle, kind]);
      if (handle === "pub") return `${ALICE}/${kind}-1764000000000.jpg`;
      if (handle === "alice" && (viewer === "bob" || viewer === ALICE)) return `${ALICE}/${kind}-1764000000000.jpg`;
      if (handle === "evil") return "../../etc/passwd";
      return null;
    },
    download: async () => opts.bytes ?? JPEG,
    quota: async () => opts.quota ?? true,
  });
  return { handler, asked };
}
const get = (path: string, token?: string, method = "GET") => new Request(`https://x.test/functions/v1/ketvoice-profile${path}`,
  { method, headers: token ? { authorization: `Bearer ${token}` } : {} });

Deno.test("a public profile's picture is served to anyone, cacheable", async () => {
  const f = fixture();
  const res = await f.handler(get("/image?handle=pub&kind=avatar"));
  assert(res.status === 200 && res.headers.get("content-type") === "image/jpeg");
  assert(res.headers.get("cache-control")!.startsWith("public"));
  assert(f.asked[0][0] === null, "an anonymous viewer");
});

Deno.test("a private profile's picture goes to friends only, never cached publicly", async () => {
  const f = fixture();
  assert((await f.handler(get("/image?handle=alice"))).status === 404);
  assert((await f.handler(get("/image?handle=alice", "stranger-token"))).status === 404);
  const friend = await f.handler(get("/image?handle=@Alice&kind=banner", "bob-token"));
  assert(friend.status === 200 && friend.headers.get("cache-control")!.startsWith("private"));
  assert(f.asked.at(-1)![1] === "alice" && f.asked.at(-1)![2] === "banner");
});

Deno.test("the anon key is anonymous, not a user", async () => {
  const f = fixture();
  assert((await f.handler(get("/image?handle=alice", ANON))).status === 404);
  assert(bearer(get("/x", ANON), ANON) === null && bearer(get("/x", "a.b.c"), ANON) === "a.b.c");
});

Deno.test("bad input, odd paths and non-JPEG bytes never leave", async () => {
  const f = fixture();
  for (const q of ["handle=x", "handle=a%20b", "handle=pub&kind=email", ""]) {
    assert((await f.handler(get("/image?" + q))).status === 400, q);
  }
  assert((await f.handler(get("/image?handle=evil"))).status === 404);
  assert((await fixture({ bytes: new TextEncoder().encode("<svg>") }).handler(get("/image?handle=pub"))).status === 404);
  assert((await f.handler(get("/image?handle=pub", undefined, "POST"))).status === 405);
  assert((await f.handler(get("/elsewhere"))).status === 404);
});

Deno.test("a 404 never says why, and the budget refuses politely", async () => {
  const f = fixture();
  const a = await (await f.handler(get("/image?handle=alice"))).text();
  const b = await (await f.handler(get("/image?handle=nobody_here"))).text();
  assert(a === b && !a.includes(ALICE));
  const busy = await fixture({ quota: false }).handler(get("/image?handle=pub"));
  assert(busy.status === 429 && busy.headers.get("retry-after") === "60");
});

Deno.test("helpers", () => {
  assert(isJpeg(JPEG) && !isJpeg(new Uint8Array([0x89, 0x50, 0x4e])));
  const req = new Request("https://x.test", { headers: { "x-forwarded-for": "203.0.113.7, 10.0.0.1" } });
  assert(clientIp(req) === "203.0.113.7" && clientIp(new Request("https://x.test")) === "unknown");
});
