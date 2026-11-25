// Remote sign-out of one of the caller's own devices ("Appareils connectes"): the device's session (and so its refresh
// tokens) is deleted on the server by ketvoice_revoke_device, with the service role, for the caller's own account only.
// The caller cannot sign out its own current session this way (that is the normal sign-out).
import { caller, type ConnectDeps, body as readBody, json, preflight, route, service, take } from "../_shared/connect.ts";

const DEVICE = /^[A-Za-z0-9-]{8,64}$/;

export function createDevicesHandler(deps: ConnectDeps) {
  async function revoke(req: Request): Promise<Response> {
    const who = await caller(req, deps);
    if (!who) return json(401, { error: "signed_out" });
    const device = (await readBody(req, 1_024))?.device_id;
    if (typeof device !== "string" || !DEVICE.test(device)) return json(400, { error: "bad_request" });
    if (!(await take(deps, `devices:${who.id}`, "devices-revoke", 20, 600))) return json(429, { error: "rate_limited" });
    const r = await service(deps, "POST", "/rest/v1/rpc/ketvoice_revoke_device",
      { p_user: who.id, p_device: device, p_caller_session: who.sessionId });
    if (!r.ok) return json(503, { error: "unavailable" });
    const outcome = await r.json();
    if (outcome === "revoked") return json(200, { status: "revoked" });
    if (outcome === "self") return json(409, { status: "self" });
    return json(404, { status: "not_found" });
  }

  return async (req: Request): Promise<Response> => {
    const early = preflight(req);
    if (early) return early;
    try {
      if (req.method === "POST" && route(req, "devices") === "/revoke") return await revoke(req);
      return json(404, { error: "not_found" });
    } catch {
      return json(503, { error: "unavailable" });
    }
  };
}
