export type Source = {id:string;name:string;manifest_url:string;enabled:boolean;created_by?:string};

function safeUrl(value: string): URL {
  let url: URL;
  try { url = new URL(value); } catch { throw new Error("URL invalide"); }
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "https:" || url.port && url.port !== "443" || url.username || url.password ||
      !host.includes(".") || host === "localhost" || host.endsWith(".localhost") ||
      host.endsWith(".local") || host.endsWith(".internal") ||
      /^\d+\.\d+\.\d+\.\d+$/.test(host) || host.includes(":") ||
      host.endsWith(".test") || host.endsWith(".invalid")) throw new Error("Hôte HTTPS public requis");
  return url;
}

export async function publicUrl(value: string): Promise<string> {
  const url = safeUrl(value);
  let resolved = 0;
  for (const type of ["A", "AAAA"] as const) {
    let addresses: string[];
    try { addresses = await Deno.resolveDns(url.hostname, type); }
    catch { continue; }
    resolved += addresses.length;
    for (const address of addresses) {
      const parts = address.split(".").map(Number);
      if (parts.length === 4 && (parts[0] === 0 || parts[0] === 10 || parts[0] === 127 ||
          parts[0] >= 224 || parts[0] === 169 && parts[1] === 254 ||
          parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31 ||
          parts[0] === 192 && parts[1] === 168 || parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127 ||
          parts[0] === 192 && parts[1] === 0 && parts[2] === 0 ||
          parts[0] === 198 && parts[1] >= 18 && parts[1] <= 19)) throw new Error("Adresse privée interdite");
      if (address.includes(":") && (/^(::1|::|fe80:|fc|fd|ff)/i.test(address) ||
          address.toLowerCase().includes("::ffff:"))) throw new Error("Adresse privée interdite");
    }
  }
  if (!resolved) throw new Error("Hôte inaccessible");
  return url.toString();
}

export async function remoteJson(value: string, timeout = 8000): Promise<unknown> {
  const url = await publicUrl(value);
  const response = await fetch(url, {redirect:"manual",signal:AbortSignal.timeout(timeout),headers:{Accept:"application/json"}});
  if (!response.ok) throw new Error("Source indisponible");
  const length = Number(response.headers.get("content-length") || 0);
  if (length > 1024 * 1024) throw new Error("Réponse trop volumineuse");
  const reader = response.body?.getReader();
  if (!reader) throw new Error("Réponse vide");
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const {value,done} = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 1024 * 1024) throw new Error("Réponse trop volumineuse");
      chunks.push(value);
    }
  } finally { await reader.cancel().catch(()=>{}); reader.releaseLock(); }
  const bytes = new Uint8Array(size); let at = 0;
  for (const chunk of chunks) { bytes.set(chunk,at); at += chunk.length; }
  return JSON.parse(new TextDecoder().decode(bytes));
}

function object(value: unknown): Record<string,unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Manifeste invalide");
  return value as Record<string,unknown>;
}

export async function manifest(value: string) {
  const url = await publicUrl(value);
  if (!new URL(url).pathname.endsWith(".json")) throw new Error("URL de manifeste JSON requise");
  const raw = object(await remoteJson(url));
  const node = raw.source && typeof raw.source === "object" ? object(raw.source) : raw;
  const base = String(node.baseUrl ?? node.base_url ?? new URL(".",url).toString());
  const endpoints = node.endpoints && typeof node.endpoints === "object" ? object(node.endpoints) : {};
  const search = String(node.search ?? node.searchUrl ?? endpoints.search ?? "search?q={query}&quality={quality}");
  const stream = String(node.stream ?? node.streamUrl ?? endpoints.stream ?? "stream/{id}?quality={quality}");
  if (!search.includes("{query}") && !search.includes("{q}") || !stream.includes("{id}")) throw new Error("Manifeste incompatible");
  const endpoint = (template: string) => new URL(template,base.endsWith("/") ? base : base + "/").toString();
  await publicUrl(endpoint(search.replaceAll("{query}","test").replaceAll("{q}","test").replaceAll("{quality}","high")));
  await publicUrl(endpoint(stream.replaceAll("{id}","test").replaceAll("{quality}","high")));
  return {search:endpoint(search),stream:endpoint(stream)};
}

export function fill(template: string, key: string, value: string): string {
  return template.replaceAll(`{${key}}`,encodeURIComponent(value))
    .replaceAll(new RegExp(`%7B${key}%7D`,"gi"),encodeURIComponent(value))
    .replaceAll("{q}",encodeURIComponent(value)).replaceAll("%7Bq%7D",encodeURIComponent(value))
    .replaceAll("{quality}","high").replaceAll("%7Bquality%7D","high");
}
