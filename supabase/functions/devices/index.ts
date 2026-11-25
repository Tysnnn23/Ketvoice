import { connectEnv } from "../_shared/connect_env.ts";
import { createDevicesHandler } from "./handler.ts";

// Deploy with verify_jwt=true: every call carries the listener's own session.
Deno.serve(createDevicesHandler({ env: connectEnv(), fetcher: fetch }));
