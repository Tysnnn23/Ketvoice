import { createArtistHandler, fanartLogo, logoUrl, musicBrainzArtist } from "./handler.ts";

const ID = "b95ce3ff-3d05-4e87-9e01-c97b66af13d4";
const IMAGE = "https://assets.fanart.tv/fanart/eminem-hd-example.png";
const PRIVATE = "unit-test-provider-credential-only";
const assert = (value: unknown, message = "Assertion failed") => { if (!value) throw new Error(message); };
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
const mb = { artists: [{ id: ID, name: "Eminem", score: 100 }] };
const fanart = { name: "Eminem", mbid_id: ID, hdmusiclogo: [{ url: IMAGE, likes: "5" }] };
function request(name = "Eminem", method = "GET") {
  return new Request(`https://example.test/ketvoice-artists/logo?artist=${encodeURIComponent(name)}`, {
    method, headers: { apikey: "public-project-key", authorization: "Bearer public-project-jwt" },
  });
}
function fixture(fetcher?: typeof fetch, quota = async () => true) {
  let calls = 0;
  const handler = createArtistHandler({ providerKey: () => PRIVATE, quota,
    fetcher: fetcher ?? (async (input) => {
      calls++;
      const url = new URL(String(input));
      if (url.hostname === "musicbrainz.org") return response(mb);
      assert(url.hostname === "webservice.fanart.tv");
      assert(url.searchParams.get("api_key") === PRIVATE);
      return response(fanart);
    }) as typeof fetch,
  });
  return { handler, count: () => calls };
}

Deno.test("missing project authentication never reaches providers", async () => {
  const f = fixture();
  const result = await f.handler(new Request("https://example.test/ketvoice-artists/logo?artist=Eminem"));
  assert(result.status === 401 && f.count() === 0);
});
Deno.test("write methods and malformed artist names are rejected", async () => {
  const f = fixture();
  assert((await f.handler(request("Eminem", "POST"))).status === 405);
  for (const name of ["x", "a".repeat(161), "Em\u0000inem"]) assert((await f.handler(request(name))).status === 400);
  assert(f.count() === 0);
});
Deno.test("observed MusicBrainz and Fanart schemas produce a key-free attributed PNG", async () => {
  const f = fixture();
  const result = await f.handler(request());
  const text = await result.text();
  const body = JSON.parse(text);
  assert(result.status === 200 && body.logo.url === IMAGE && body.logo.kind === "logo");
  assert(body.mbid === ID && body.logo.source === `https://fanart.tv/artist/${ID}/`);
  assert(!text.includes(PRIVATE) && !text.includes("api_key") && f.count() === 2);
});
Deno.test("same-artist requests coalesce and later requests use the cache", async () => {
  const f = fixture();
  const results = await Promise.all([f.handler(request()), f.handler(request())]);
  assert(results.every(r => r.status === 200));
  assert((await f.handler(request("eminem"))).status === 200 && f.count() === 2);
});
Deno.test("a quota backend refusal fails closed without spending provider calls", async () => {
  const f = fixture(undefined, async () => false);
  assert((await f.handler(request())).status === 429 && f.count() === 0);
});
Deno.test("provider error bodies and credentials never reach the client", async () => {
  const f = fixture((async (input) => String(input).includes("musicbrainz.org") ? response(mb) : response({ error: PRIVATE }, 401)) as typeof fetch);
  const result = await f.handler(request());
  assert(result.status === 503 && !(await result.text()).includes(PRIVATE));
});
Deno.test("provider 429 starts a cooldown instead of repeated upstream calls", async () => {
  let calls = 0;
  const f = fixture((async () => { calls++; return response({}, 429); }) as typeof fetch);
  const first = await f.handler(request());
  const second = await f.handler(request("Other artist"));
  assert(first.status === 429 && second.status === 429 && second.headers.get("retry-after") === "60" && calls === 1);
});
Deno.test("bounded JSON reading rejects oversized provider responses", async () => {
  const f = fixture((async () => new Response(" ".repeat(1_000_001))) as typeof fetch);
  assert((await f.handler(request())).status === 503);
});
Deno.test("similar names and ambiguous MusicBrainz matches are not guessed", () => {
  assert(musicBrainzArtist(mb, "Other artist") === null);
  assert(musicBrainzArtist({ artists: [...mb.artists, { id: "00000000-0000-0000-0000-000000000000", name: "Eminem", score: 100 }] }, "Eminem") === null);
  assert(musicBrainzArtist(mb, " eminem ")?.id === ID);
});
Deno.test("wrong identities, arbitrary hosts and credential-bearing asset URLs are rejected", () => {
  assert(fanartLogo({ ...fanart, mbid_id: "other" }, ID) === null);
  for (const url of [IMAGE.replace("assets.fanart.tv", "evil.example"), IMAGE + "?api_key=private", IMAGE.replace("/fanart/", "/private/"), IMAGE.replace("https://", "https://user:pass@")])
    assert(logoUrl(url, ID) === null);
  assert(logoUrl(IMAGE.replace("https:", "http:"), ID) === IMAGE);
});
Deno.test("HD is preferred and standard logo fallback is ranked by likes", () => {
  const standard = IMAGE.replace("hd-", "standard-");
  assert(fanartLogo({ ...fanart, musiclogo: [{ url: standard, likes: "99" }] }, ID) === IMAGE);
  assert(fanartLogo({ mbid_id: ID, musiclogo: [{ url: standard, likes: "1" }, { url: standard.replace("example", "popular"), likes: "20" }] }, ID) === standard.replace("example", "popular"));
});
Deno.test("per-IP burst limit applies even when results are cached", async () => {
  const f = fixture();
  for (let i = 0; i < 12; i++) assert((await f.handler(request())).status === 200);
  assert((await f.handler(request())).status === 429 && f.count() === 2);
});
