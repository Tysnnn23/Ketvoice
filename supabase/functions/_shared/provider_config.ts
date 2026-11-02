import registry from "../../../app/src/main/assets/provider_registry.json" with { type: "json" };
import { publicUrl } from "./managed_sources.ts";

export type ProviderRule = {id: string; enabled: boolean; base: string};
export const providers = registry as Array<{id:string;name:string;category:string;base:string;editable:boolean;toggle?:boolean;note:string}>;

// Never allow arbitrary JSON settings, credential query strings or changes to
// authentication trust anchors. These public JSON adapters never carry cookies.
export async function validateRules(value: unknown): Promise<ProviderRule[]> {
  if (!Array.isArray(value) || value.length > providers.length) throw new Error("Configuration invalide");
  const ids = new Set<string>();
  const out: ProviderRule[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== "object" || Object.keys(raw).some(k => !["id","enabled","base"].includes(k)))
      throw new Error("Champs invalides");
    const row = raw as ProviderRule;
    const def = providers.find(p => p.id === row.id);
    if (!def || (!def.editable && !def.toggle) || ids.has(row.id) || typeof row.enabled !== "boolean" || typeof row.base !== "string")
      throw new Error("Service non modifiable ou dupliqué");
    ids.add(row.id);
    if (def.editable) {
      const url = new URL(row.base);
      if (url.search || url.hash || url.username || url.password || row.base.length > 512 || !url.pathname.endsWith("/"))
        throw new Error("Base HTTPS publique sans identifiant, paramètre ni fragment, terminée par / requise");
      // Packaged defaults are already trusted. Their temporary DNS outage must
      // not prevent the owner from disabling a failing provider or resetting it.
      if (row.base !== def.base) await publicUrl(row.base);
    } else if (row.base !== "") throw new Error("Adresse protégée côté serveur");
    out.push({id:row.id, enabled:row.enabled, base:row.base});
  }
  return out;
}
