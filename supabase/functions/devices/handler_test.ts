import { assert, FakeProject, jwt, req } from "../_shared/connect_fake.ts";
import { createDevicesHandler } from "./handler.ts";

const SESSION = "a0000000-0000-4000-8000-0000000000a1";
const ALICE = { id: "aaaaaaaa-0000-4000-8000-000000000001", email: "alice@example.test", token: jwt({ sub: "alice", session_id: SESSION }, "alice") };

Deno.test("a device is revoked for the caller's own account, with the caller's session id", async () => {
  const project = new FakeProject();
  project.users.push(ALICE);
  let seen: Record<string, unknown> = {};
  project.revoke = (args) => { seen = args; return "revoked"; };
  const handler = createDevicesHandler(project.deps());
  const r = await handler(req("/devices/revoke", { token: ALICE.token, body: { device_id: "phone-device-01", p_user: "someone-else" } }));
  assert(r.status === 200);
  assert(seen.p_user === ALICE.id && seen.p_device === "phone-device-01" && seen.p_caller_session === SESSION);
});

Deno.test("its own session, unknown devices, bad ids and signed-out callers are refused", async () => {
  const project = new FakeProject();
  project.users.push(ALICE);
  const handler = createDevicesHandler(project.deps());
  project.revoke = () => "self";
  assert((await handler(req("/devices/revoke", { token: ALICE.token, body: { device_id: "pc-device-0001" } }))).status === 409);
  project.revoke = () => "not_found";
  assert((await handler(req("/devices/revoke", { token: ALICE.token, body: { device_id: "pc-device-0002" } }))).status === 404);
  assert((await handler(req("/devices/revoke", { token: ALICE.token, body: { device_id: "x';--" } }))).status === 400);
  assert((await handler(req("/devices/revoke", { token: jwt({ sub: "x" }, "x"), body: { device_id: "pc-device-0002" } }))).status === 401);
});
