import { assert, FakeProject, jwt, req } from "../_shared/connect_fake.ts";
import { CONFIRM, createMyDataHandler } from "./handler.ts";

const ALICE = { id: "aaaaaaaa-0000-4000-8000-000000000001", email: "alice@example.test", token: jwt({ sub: "alice" }, "alice") };
const BOB = { id: "bbbbbbbb-0000-4000-8000-000000000002", email: "bob@example.test", token: jwt({ sub: "bob" }, "bob") };
const OWNER = { id: "cccccccc-0000-4000-8000-000000000003", email: "konnektsupport@gmail.com", token: jwt({ sub: "owner" }, "owner") };

function setup() {
  const project = new FakeProject();
  project.users.push(ALICE, BOB, OWNER);
  project.table("ketvoice_profiles").push({ id: ALICE.id, display_name: "Alice" }, { id: BOB.id, display_name: "Bob" });
  project.table("ketvoice_library").push({ user_id: ALICE.id, kind: "favorites", doc: {} }, { user_id: BOB.id, kind: "favorites", doc: {} });
  project.table("ketvoice_lastfm_links").push({ user_id: ALICE.id, username: "alicefm", session_key: "sk-secret-0001", created_at: "2026-11-20T10:00:00Z" });
  project.objects.set("ketvoice-avatars", [`${ALICE.id}/avatar-1700000000000.jpg`, `${BOB.id}/avatar-1700000000001.jpg`]);
  return { project, handler: createMyDataHandler(project.deps()) };
}

Deno.test("the export holds the caller's rows only, and no Last.fm session key", async () => {
  const { handler } = setup();
  const r = await handler(req("/my-data/export", { token: ALICE.token }));
  const text = await r.text();
  const data = JSON.parse(text);
  assert(r.status === 200 && data.account.id === ALICE.id);
  assert(data.tables.ketvoice_profiles.length === 1 && data.tables.ketvoice_library.length === 1);
  assert(!text.includes(BOB.id) && !text.includes("sk-secret") && !text.includes("service-key-secret"));
  assert(data.lastfm.username === "alicefm" && data.photos["ketvoice-avatars"].length === 1);
});

Deno.test("deleting needs the typed word, removes photos, then the account", async () => {
  const { project, handler } = setup();
  assert((await handler(req("/my-data/delete", { token: ALICE.token, body: { confirm: "oui" } }))).status === 400);
  assert(project.deletedUsers.length === 0);
  const r = await handler(req("/my-data/delete", { token: ALICE.token, body: { confirm: CONFIRM } }));
  assert(r.status === 200 && project.deletedUsers.join() === ALICE.id);
  assert(project.objects.get("ketvoice-avatars")!.join() === `${BOB.id}/avatar-1700000000001.jpg`);
});

Deno.test("the publishing account is never deleted here, nor anyone signed out", async () => {
  const { project, handler } = setup();
  assert((await handler(req("/my-data/delete", { token: OWNER.token, body: { confirm: CONFIRM } }))).status === 403);
  assert((await handler(req("/my-data/delete", { token: jwt({ sub: "x" }, "x"), body: { confirm: CONFIRM } }))).status === 401);
  assert(project.deletedUsers.length === 0);
});
