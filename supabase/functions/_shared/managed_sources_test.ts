import {fill,publicUrl} from "./managed_sources.ts";

Deno.test("source templates encode song identifiers", () => {
  const url=fill("https://example.org/search?q={query}&quality={quality}","query","é & test");
  if(url!=="https://example.org/search?q=%C3%A9%20%26%20test&quality=high") throw new Error(url);
  const stream=fill("https://example.org/stream/%7Bid%7D?quality={quality}","id","a/b");
  if(stream!=="https://example.org/stream/a%2Fb?quality=high") throw new Error(stream);
});

Deno.test("private and credential-bearing source URLs are rejected", async () => {
  for(const url of ["http://example.org/manifest.json","https://127.0.0.1/manifest.json",
    "https://[::1]/manifest.json","https://user:password@example.org/manifest.json",
    "https://localhost/manifest.json","https://example.org:8443/manifest.json"]) {
    let rejected=false;
    try{await publicUrl(url);}catch{rejected=true;}
    if(!rejected) throw new Error(url);
  }
});
