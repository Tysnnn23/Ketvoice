import { connectEnv } from "../_shared/connect_env.ts";
import { createLinkHandler } from "./handler.ts";

// Deploy with verify_jwt=true: /start and /poll are called with the public anon key, /info, /approve and /deny with
// the approving listener's session.
Deno.serve(createLinkHandler({ env: connectEnv(), fetcher: fetch }));
