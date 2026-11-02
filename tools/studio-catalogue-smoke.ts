// Direct upstream smoke; no authenticated Studio session and no media playback.
import {searchCatalogue,catalogueDetail} from "../supabase/functions/_shared/studio_catalogue.ts";
const source=await Deno.readTextFile("app/src/main/kotlin/com/ketvoice/app/data/KetvoiceApi.kt");
const key=source.match(/const val ANON_KEY\s*=\s*"([^"]+)"/)?.[1];
if(!key)throw new Error("Public project key missing");
Deno.env.set("SUPABASE_URL","https://xodklrunqlzyeiqtovgx.supabase.co");Deno.env.set("SUPABASE_ANON_KEY",key);
const evidence:unknown[]=[];
for (const kind of ["track","album","artist"]) {
  const result=await searchCatalogue("Eminem",kind,async()=>[]);
  const results=result.results as Array<Record<string,unknown>>;
  const item={kind,count:results.length,first:results.slice(0,2).map(r=>({id:r.id,title:r.title,artist:r.artist})),warnings:result.warnings};
  console.log(JSON.stringify(item));evidence.push(item);
  if(!results.length)throw new Error("No live catalogue results for "+kind);
  if(kind==="album"){
    const detail=await catalogueDetail(String(results[0].id),kind);
    console.log(JSON.stringify({albumTracks:detail.tracks.length}));evidence.push({albumTracks:detail.tracks.length});
    if(!detail.tracks.length)throw new Error("Empty live album detail");
  }
}
await Deno.writeTextFile("build/studio-catalogue-live.json",JSON.stringify({captured_at:new Date().toISOString(),scope:"direct upstream catalogue, not a real owner session or playback",results:evidence},null,2));
