import { assert, FakeProject, jwt, req } from "../_shared/connect_fake.ts";
import { createLastFmHandler, listen, scrobbleParams, sign } from "./handler.ts";
import { md5 } from "./md5.ts";

const ALICE = { id: "aaaaaaaa-0000-4000-8000-000000000001", email: "alice@example.test", token: jwt({ sub: "alice" }, "alice") };
const KEY = "public-api-key-0001";
const SECRET = "shared-secret-never-sent";

Deno.test("md5 matches RFC 1321's vectors", () => {
  assert(md5("") === "d41d8cd98f00b204e9800998ecf8427e");
  assert(md5("abc") === "900150983cd24fb0d6963f7d28e17f72");
  assert(md5("The quick brown fox jumps over the lazy dog") === "9e107d9d372bb6826bd81d3542a419d6");
  assert(md5("12345678901234567890123456789012345678901234567890123456789012345678901234567890") === "57edf4a22be3c955ac49da2e2107b67a");
  // UTF-8 bytes, and the padding edges (56 and 64 bytes).
  assert(md5("\u00e9") === "66ddcd97cfdeabb2f6fb8a999b4bc76f");
  assert(md5("a".repeat(56)) === "3b0c8ac703f828b04c6c197006d17218");
  assert(md5("a".repeat(64)) === "014842d480b571495a4a0363793f7367");
});

Deno.test("signatures sort the parameters and leave format out", () => {
  const p = { method: "auth.getSession", api_key: "k", token: "t", format: "json" };
  assert(sign(p, "s") === md5("api_keykmethodauth.getSessiontokents"));
});

Deno.test("listens below 30 s or unnamed are refused; batches are indexed", () => {
  assert(listen({ title: "T", artist: "A", durationMs: 20_000, at: 1 }) === null);
  assert(listen({ title: " ", artist: "A", at: 1 }) === null);
  const items = [listen({ title: "T", artist: "A", album: "B", durationMs: 200_000, at: 1_700_000_000_999 })!];
  const p = scrobbleParams(items, "sk", KEY);
  assert(p["timestamp[0]"] === "1700000000" && p["duration[0]"] === "200" && p["album[0]"] === "B" && p.sk === "sk");
});

function setup() {
  const project = new FakeProject();
  project.users.push(ALICE);
  const sent: URLSearchParams[] = [];
  project.external = async (url, init) => {
    assert(url.hostname === "ws.audioscrobbler.com");
    const params = new URLSearchParams(String(init.body));
    sent.push(params);
    const copy = Object.fromEntries([...params.entries()].filter(([k]) => k !== "api_sig"));
    assert(params.get("api_sig") === sign(copy, SECRET), "signed");
    switch (params.get("method")) {
      case "auth.getToken": return new Response(JSON.stringify({ token: "tok0123456789" }));
      case "auth.getSession": return new Response(JSON.stringify({ session: { name: "alicefm", key: "sk-0123456789", subscriber: 0 } }));
      case "track.updateNowPlaying": return new Response(JSON.stringify({ nowplaying: {} }));
      case "track.scrobble": return new Response(JSON.stringify({ scrobbles: { "@attr": { accepted: 2, ignored: 0 } } }));
    }
    return new Response(JSON.stringify({ error: 6 }), { status: 400 });
  };
  const handler = createLastFmHandler({ ...project.deps(), apiKey: () => KEY, secret: () => SECRET });
  const call = (path: string, body: unknown = {}) => handler(req(`/lastfm${path}`, { token: ALICE.token, body }));
  return { project, handler, sent, call };
}

Deno.test("linking keeps the session key on the server and never returns secrets", async () => {
  const { project, sent, call } = setup();
  const started = await (await call("/auth/start")).json();
  assert(started.url === `https://www.last.fm/api/auth/?api_key=${KEY}&token=tok0123456789`);
  const done = await call("/auth/complete");
  const text = await done.text();
  assert(done.status === 200 && JSON.parse(text).name === "alicefm" && !text.includes("sk-0123456789"));
  assert(project.table("ketvoice_lastfm_links")[0].session_key === "sk-0123456789");
  const status = await (await call("/status")).text();
  assert(JSON.parse(status).connected === true && !status.includes("sk-") && !status.includes(SECRET));
  assert(sent.every((p) => !p.has("secret") && ![...p.values()].includes(SECRET)));
});

Deno.test("now playing and scrobbles are signed with the listener's key", async () => {
  const { sent, call } = setup();
  assert((await call("/scrobble", { scrobbles: [{ title: "T", artist: "A", at: 1 }] })).status === 409);
  await call("/auth/start");
  await call("/auth/complete");
  assert((await call("/nowplaying", { track: { title: "Midnight City", artist: "M83", durationMs: 240_000 } })).status === 200);
  const r = await call("/scrobble", { scrobbles: [
    { title: "Midnight City", artist: "M83", durationMs: 240_000, at: Date.parse("2026-11-25T11:00:00Z") },
    { title: "Wait", artist: "M83", durationMs: 340_000, at: Date.parse("2026-11-25T11:05:00Z") },
    { title: "Too short", artist: "M83", durationMs: 10_000, at: Date.parse("2026-11-25T11:09:00Z") },
  ] });
  assert(r.status === 200 && (await r.json()).accepted === 2);
  const batch = sent.find((p) => p.get("method") === "track.scrobble")!;
  assert(batch.get("sk") === "sk-0123456789" && batch.get("track[1]") === "Wait" && !batch.has("track[2]"));
});

Deno.test("an invalid session key unlinks; signed-out callers and missing secrets are refused", async () => {
  const { project, call, handler } = setup();
  await call("/auth/start");
  await call("/auth/complete");
  project.external = async () => new Response(JSON.stringify({ error: 9, message: "Invalid session key" }), { status: 403 });
  assert((await call("/nowplaying", { track: { title: "T", artist: "A" } })).status === 409);
  assert(project.table("ketvoice_lastfm_links").length === 0);
  assert((await handler(req("/lastfm/status", { token: jwt({ sub: "x" }, "x"), body: {} }))).status === 401);
  const bare = createLastFmHandler({ ...project.deps(), apiKey: () => "", secret: () => "" });
  assert((await bare(req("/lastfm/status", { token: ALICE.token, body: {} }))).status === 503);
});
