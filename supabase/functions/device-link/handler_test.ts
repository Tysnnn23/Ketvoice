import { assert, FakeProject, jwt, req } from "../_shared/connect_fake.ts";
import { ALPHABET, createLinkHandler, newCode, normalize } from "./handler.ts";

const ALICE = { id: "aaaaaaaa-0000-4000-8000-000000000001", email: "alice@example.test", token: jwt({ sub: "alice" }, "alice") };
const OWNER = { id: "bbbbbbbb-0000-4000-8000-000000000002", email: "KonnektSupport@gmail.com", token: jwt({ sub: "owner" }, "owner") };
const T0 = Date.parse("2026-11-25T12:00:00Z");

function setup() {
  const project = new FakeProject();
  project.users.push(ALICE, OWNER);
  let clock = T0;
  const deps = { ...project.deps(() => clock), random: (b: Uint8Array) => { for (let i = 0; i < b.length; i++) b[i] = (i * 37 + 11) & 255; return b; } };
  const handler = createLinkHandler(deps);
  return { project, handler, tick: (ms: number) => { clock += ms; } };
}

async function start(handler: (r: Request) => Promise<Response>) {
  const r = await handler(req("/device-link/start", { body: { name: "DESKTOP-4F2K\u0007", kind: "pc" } }));
  assert(r.status === 200, "start " + r.status);
  return await r.json();
}

Deno.test("codes use the unambiguous alphabet and typed codes are normalised", () => {
  const code = newCode((b) => b.map((_, i) => i * 31));
  assert(code.length === 8 && [...code].every((c) => ALPHABET.includes(c)));
  assert(normalize("abcd-efgh") === "ABCDEFGH");
  assert(normalize("ABCD-EF01") === null && normalize("ABC") === null && normalize(42) === null);
});

Deno.test("a PC gets a code and a secret; only the secret's digest is stored", async () => {
  const { project, handler } = setup();
  const s = await start(handler);
  assert(/^[A-Z2-9]{8}$/.test(s.code) && s.secret.length === 43 && s.expires_in === 300);
  const row = project.table("ketvoice_device_links")[0];
  assert(row.device_name === "DESKTOP-4F2K" && row.secret_hash !== s.secret && /^[0-9a-f]{64}$/.test(String(row.secret_hash)));
  assert(!JSON.stringify(project.tables).includes(s.secret));
});

Deno.test("start is rate limited per address", async () => {
  const { project, handler } = setup();
  project.limitOk = false;
  assert((await handler(req("/device-link/start", { body: {} }))).status === 429);
});

Deno.test("pending, approved once, then used", async () => {
  const { handler, tick } = setup();
  const s = await start(handler);
  const poll = async () => await (await handler(req("/device-link/poll", { body: { code: s.code, secret: s.secret } }))).json();
  assert((await poll()).status === "pending");
  assert((await poll()).status === "slow_down");
  const info = await (await handler(req(`/device-link/info?code=${s.code.toLowerCase()}`, { token: ALICE.token }))).json();
  assert(info.status === "pending" && info.device_name === "DESKTOP-4F2K");
  assert((await handler(req("/device-link/approve", { token: ALICE.token, body: { code: s.code } }))).status === 200);
  assert((await handler(req("/device-link/approve", { token: ALICE.token, body: { code: s.code } }))).status === 409);
  tick(3_000);
  const approved = await poll();
  assert(approved.status === "approved" && approved.token_hash.startsWith("hashed-"));
  tick(3_000);
  assert((await poll()).status === "used");
});

Deno.test("a wrong secret learns nothing and a code expires after 5 minutes", async () => {
  const { handler, tick } = setup();
  const s = await start(handler);
  const wrong = await handler(req("/device-link/poll", { body: { code: s.code, secret: "A".repeat(43) } }));
  assert(wrong.status === 404 && (await wrong.json()).status === "expired");
  tick(301_000);
  assert((await (await handler(req("/device-link/poll", { body: { code: s.code, secret: s.secret } }))).json()).status === "expired");
  assert((await handler(req("/device-link/approve", { token: ALICE.token, body: { code: s.code } }))).status === 409);
});

Deno.test("the publishing account and signed-out callers cannot approve; a denial reaches the PC", async () => {
  const { handler, tick } = setup();
  const s = await start(handler);
  assert((await handler(req("/device-link/approve", { token: OWNER.token, body: { code: s.code } }))).status === 403);
  assert((await handler(req("/device-link/approve", { token: jwt({ sub: "nobody" }, "nobody"), body: { code: s.code } }))).status === 401);
  assert((await handler(req("/device-link/deny", { token: ALICE.token, body: { code: s.code } }))).status === 200);
  tick(3_000);
  assert((await (await handler(req("/device-link/poll", { body: { code: s.code, secret: s.secret } }))).json()).status === "denied");
});

Deno.test("no sign-in token is ever minted for the publishing account", async () => {
  const { project, handler, tick } = setup();
  const s = await start(handler);
  // Even if a row named the owner (it cannot through /approve), /poll refuses to sign them in.
  Object.assign(project.table("ketvoice_device_links")[0], { approved_by: OWNER.id, approved_at: new Date(T0).toISOString() });
  tick(3_000);
  const answer = await (await handler(req("/device-link/poll", { body: { code: s.code, secret: s.secret } }))).json();
  assert(answer.status === "denied" && !("token_hash" in answer));
  assert(!project.calls.some((c) => c.endsWith("/generate_link")));
});
