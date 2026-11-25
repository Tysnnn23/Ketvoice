// The hosted environment of the Connexions functions. Nothing here is logged.
import type { ConnectEnv } from "./connect.ts";

export function connectEnv(): ConnectEnv {
  const env = (name: string) => (Deno.env.get(name) ?? "").trim();
  return { url: env("SUPABASE_URL"), anonKey: env("SUPABASE_ANON_KEY"), serviceKey: env("SUPABASE_SERVICE_ROLE_KEY") };
}
