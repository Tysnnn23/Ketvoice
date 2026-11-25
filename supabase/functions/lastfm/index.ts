import { connectEnv } from "../_shared/connect_env.ts";
import { createLastFmHandler } from "./handler.ts";

// Deploy with verify_jwt=true. LASTFM_API_KEY and LASTFM_SHARED_SECRET are edge-function secrets set by the owner;
// they are never returned, logged or sent anywhere but ws.audioscrobbler.com (the key alone also appears in the
// Last.fm address where the listener allows Ketvoice, as Last.fm's desktop flow requires).
const env = (name: string) => (Deno.env.get(name) ?? "").trim();
Deno.serve(createLastFmHandler({ env: connectEnv(), fetcher: fetch, apiKey: () => env("LASTFM_API_KEY"), secret: () => env("LASTFM_SHARED_SECRET") }));
