import { connectEnv } from "../_shared/connect_env.ts";
import { createMyDataHandler } from "./handler.ts";

// Deploy with verify_jwt=true: every call carries the listener's own session; the service role key stays here.
Deno.serve(createMyDataHandler({ env: connectEnv(), fetcher: fetch }));
