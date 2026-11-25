// Test double of the Supabase project for the Connexions functions' tests: PostgREST tables kept in memory (the
// filters these functions use: eq, is.null, not.is.null, gt, lt), the rate-limit RPC, Auth's /user and admin routes,
// Storage's list/delete, and the revoke RPC. Never used by a deployed function.
import type { ConnectDeps } from "./connect.ts";

export type Row = Record<string, unknown>;

export interface FakeUser { id: string; email: string; token: string }

export class FakeProject {
  tables = new Map<string, Row[]>();
  users: FakeUser[] = [];
  objects = new Map<string, string[]>();
  calls: string[] = [];
  limitOk = true;
  revoke: (args: Row) => string = () => "revoked";
  external: (url: URL, init: RequestInit) => Promise<Response> = () => Promise.resolve(new Response("{}", { status: 404 }));
  deletedUsers: string[] = [];

  deps(now = () => Date.parse("2026-11-25T12:00:00Z")): ConnectDeps {
    return { env: { url: "https://project.test", anonKey: "anon-key", serviceKey: "service-key-secret" }, fetcher: this.fetcher, now };
  }

  table(name: string): Row[] {
    if (!this.tables.has(name)) this.tables.set(name, []);
    return this.tables.get(name)!;
  }

  static matches(row: Row, filters: Array<[string, string]>): boolean {
    return filters.every(([col, cond]) => {
      const v = row[col];
      if (cond === "is.null") return v === null || v === undefined;
      if (cond === "not.is.null") return v !== null && v !== undefined;
      const [op, ...rest] = cond.split(".");
      const arg = decodeURIComponent(rest.join("."));
      if (op === "eq") return String(v) === arg;
      const a = Date.parse(String(v)), b = Date.parse(arg);
      if (op === "gt") return a > b;
      if (op === "lt") return a < b;
      return false;
    });
  }

  private static filters(url: URL): Array<[string, string]> {
    return [...url.searchParams.entries()].filter(([k]) => !["select", "on_conflict", "limit", "order"].includes(k));
  }

  private json(status: number, body: unknown) { return new Response(JSON.stringify(body), { status }); }

  private bearer(init: RequestInit): string {
    const h = new Headers(init.headers);
    return (h.get("authorization") ?? "").replace(/^Bearer /, "");
  }

  fetcher = (async (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
    const url = new URL(String(input));
    const method = (init.method ?? "GET").toUpperCase();
    this.calls.push(`${method} ${url.hostname}${url.pathname}`);
    if (url.hostname !== "project.test") return this.external(url, init);
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    const path = url.pathname;
    if (path === "/auth/v1/user") {
      const user = this.users.find((u) => u.token === this.bearer(init));
      return user ? this.json(200, { id: user.id, email: user.email }) : this.json(401, {});
    }
    const service = this.bearer(init) === "service-key-secret";
    if (path.startsWith("/auth/v1/admin/") && !service) return this.json(403, {});
    if (path.startsWith("/auth/v1/admin/users/")) {
      const id = path.split("/").pop()!;
      const user = this.users.find((u) => u.id === id);
      if (!user) return this.json(404, {});
      if (method === "DELETE") { this.deletedUsers.push(id); this.users = this.users.filter((u) => u.id !== id); return this.json(200, {}); }
      return this.json(200, { id: user.id, email: user.email });
    }
    if (path === "/auth/v1/admin/generate_link") return this.json(200, { hashed_token: "hashed-" + "x".repeat(30), verification_type: "magiclink" });
    if (path === "/rest/v1/rpc/ketvoice_api_take") return this.json(200, this.limitOk);
    if (path === "/rest/v1/rpc/ketvoice_revoke_device") return this.json(200, this.revoke(body));
    if (path.startsWith("/storage/v1/object/list/")) {
      const bucket = path.split("/").pop()!;
      const names = (this.objects.get(bucket) ?? []).filter((n) => n.startsWith(body.prefix)).map((n) => ({ name: n.slice(body.prefix.length) }));
      return this.json(200, names);
    }
    if (path.startsWith("/storage/v1/object/") && method === "DELETE") {
      const bucket = path.split("/").pop()!;
      this.objects.set(bucket, (this.objects.get(bucket) ?? []).filter((n) => !body.prefixes.includes(n)));
      return this.json(200, []);
    }
    if (path.startsWith("/rest/v1/")) return this.rest(method, path.slice("/rest/v1/".length), url, body, service, init);
    return this.json(404, {});
  }) as typeof fetch;

  private rest(method: string, name: string, url: URL, body: Row | undefined, service: boolean, init: RequestInit): Response {
    const rows = this.table(name);
    const filters = FakeProject.filters(url);
    if (!service) {
      // As a listener: only rows of their own account (the filter names it), never the service-only tables.
      if (["ketvoice_device_links", "ketvoice_lastfm_links"].includes(name)) return this.json(401, {});
      const user = this.users.find((u) => u.token === this.bearer(init));
      if (!user) return this.json(401, {});
      return this.json(200, rows.filter((r) => FakeProject.matches(r, filters) && Object.values(r).includes(user.id)));
    }
    if (method === "GET") return this.json(200, rows.filter((r) => FakeProject.matches(r, filters)));
    if (method === "POST") {
      const key = url.searchParams.get("on_conflict") ?? (name === "ketvoice_device_links" ? "code" : "user_id");
      const existing = rows.find((r) => r[key] === body![key]);
      if (existing && !(init.headers && new Headers(init.headers).get("prefer")?.includes("merge-duplicates"))) return this.json(409, {});
      if (existing) Object.assign(existing, body); else rows.push({ ...body });
      return new Response(null, { status: 201 });
    }
    if (method === "PATCH") {
      const hit = rows.filter((r) => FakeProject.matches(r, filters));
      hit.forEach((r) => Object.assign(r, body));
      return this.json(200, hit);
    }
    if (method === "DELETE") {
      this.tables.set(name, rows.filter((r) => !FakeProject.matches(r, filters)));
      return new Response(null, { status: 204 });
    }
    return this.json(405, {});
  }
}

export const assert = (value: unknown, message = "Assertion failed") => { if (!value) throw new Error(message); };

export function req(path: string, init: { method?: string; token?: string; body?: unknown; ip?: string } = {}): Request {
  const headers: Record<string, string> = { apikey: "anon-key", "x-forwarded-for": init.ip ?? "203.0.113.7" };
  headers.authorization = `Bearer ${init.token ?? "aaa.bbb.ccc"}`;
  return new Request(`https://project.test/functions/v1${path}`, {
    method: init.method ?? (init.body === undefined ? "GET" : "POST"), headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
}

/** A token shaped like a JWT whose payload carries [claims] (signature checks are the fake /auth/v1/user's). */
export function jwt(claims: Record<string, unknown>, tag = "sig"): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "HS256" })}.${b64(claims)}.${tag}`;
}
