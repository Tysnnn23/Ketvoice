type Row=Record<string,any>;
const filters:Record<string,string>={track:"EgWKAQIIAWoKEAkQChAFEAMQBA%3D%3D",album:"EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D",artist:"EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"};
function text(value:any):string { return String(value?.simpleText || value?.runs?.map((r:Row)=>r.text || "").join("") || "").slice(0,500); }
function objects(root:any,key:string):Row[] {
  const out:Row[]=[],stack=[root]; let count=0;
  while(stack.length && ++count<50000) {
    const item=stack.pop();
    if(!item || typeof item!=="object")continue;
    if(item[key] && typeof item[key]==="object")out.push(item[key]);
    for(const value of Object.values(item))if(value && typeof value==="object")stack.push(value);
  }
  return out.reverse();
}
function image(row:Row):string|null {
  const candidates=objects(row,"musicThumbnailRenderer").flatMap(t=>t.thumbnail?.thumbnails || []);
  return candidates.sort((a,b)=>(b.width||0)-(a.width||0)).find(t=>typeof t.url==="string" && t.url.startsWith("https://"))?.url || null;
}
export function parseCatalogue(root:Row,kind:string):Row[] {
  const seen=new Set<string>(),out:Row[]=[];
  for(const row of [...objects(root,"musicResponsiveListItemRenderer"),...objects(root,"musicTwoRowItemRenderer")]) {
    const columns=(row.flexColumns || []).map((c:Row)=>c.musicResponsiveListItemFlexColumnRenderer?.text);
    const title=text(columns[0] || row.title);
    const runs=columns.flatMap((c:Row)=>c?.runs || []);
    const endpoints=[row.navigationEndpoint,...runs.map((r:Row)=>r.navigationEndpoint)].filter(Boolean);
    const video=row.playlistItemData?.videoId || endpoints.find((e:Row)=>e.watchEndpoint?.videoId)?.watchEndpoint?.videoId;
    const browse=endpoints.map((e:Row)=>e.browseEndpoint?.browseId).filter(Boolean);
    const artists=runs.filter((r:Row)=>String(r.navigationEndpoint?.browseEndpoint?.browseId || "").startsWith("UC")).map((r:Row)=>r.text);
    const album=runs.find((r:Row)=>/^MPRE/.test(r.navigationEndpoint?.browseEndpoint?.browseId || ""))?.text || "";
    let id="";
    if(kind==="track" && /^[\w-]{11}$/.test(video || ""))id=`yt:${video}`;
    if(kind==="album")id=browse.find((s:string)=>s.startsWith("MPRE")) || "";
    if(kind==="artist")id=browse.find((s:string)=>s.startsWith("UC")) || "";
    if(!id || !title || seen.has(id))continue;
    seen.add(id);
    out.push({id,kind,title,artist:kind==="artist"?title:artists.join(", "),album,artworkURL:image(row),source:"YouTube Music",editable:false,
      url:kind==="track"?`https://music.youtube.com/watch?v=${video}`:`https://music.youtube.com/browse/${encodeURIComponent(id)}`});
    if(out.length>=40)break;
  }
  return out;
}
async function readJson(response:Response):Promise<Row> {
  if(!response.ok || !response.body)throw new Error("Catalogue inaccessible ("+response.status+")");
  const reader=response.body.getReader(),chunks:Uint8Array[]=[];let size=0;
  try { while(true) {const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>4*1024*1024){await reader.cancel();throw new Error("Réponse trop volumineuse");}chunks.push(value);} }
  finally {reader.releaseLock();}
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
  return JSON.parse(new TextDecoder().decode(bytes));
}
async function youtube(path:string,fields:Row):Promise<Row> {
  return await readJson(await fetch(`https://music.youtube.com/youtubei/v1/${path}`,{method:"POST",redirect:"error",signal:AbortSignal.timeout(12000),
    headers:{"content-type":"application/json","User-Agent":"Mozilla/5.0","Origin":"https://music.youtube.com"},
    body:JSON.stringify({context:{client:{clientName:"WEB_REMIX",clientVersion:"1.20241014.01.00",hl:"fr",gl:"FR"}},...fields})}));
}
export async function catalogueDetail(id:string,kind:string):Promise<Row> {
  if(!["album","artist"].includes(kind) || !(kind==="artist"?/^UC[\w-]{10,80}$/:/^MPRE[\w-]{8,100}$/).test(id))throw new Error("Identifiant invalide");
  const root=await youtube("browse",{browseId:id});
  return {tracks:parseCatalogue(root,"track"),albums:parseCatalogue(root,"album"),artists:parseCatalogue(root,"artist")};
}
export async function searchCatalogue(q:string,kind:string,publications:()=>Promise<Row[]>):Promise<Row> {
  const warnings:string[]=[];
  const [yt,uploads,broker]=await Promise.all([
    (async()=>{
      let rows:Row[]=[];
      try{rows=parseCatalogue(await youtube("search",{query:q,params:filters[kind]}),kind);}catch{/* Retry using the app's unfiltered fallback below. */}
      if(rows.length)return rows;
      try{return parseCatalogue(await youtube("search",{query:q}),kind);}catch{warnings.push("YouTube Music indisponible pour cette recherche.");return [];}
    })(),
    publications(),
    kind==="track" ? (async()=>{
      try {
        const base=Deno.env.get("SUPABASE_URL")!,key=Deno.env.get("SUPABASE_ANON_KEY")!;
        const data=await readJson(await fetch(`${base}/functions/v1/ketvoice-api/search?q=${encodeURIComponent(q)}`,{headers:{apikey:key,Authorization:`Bearer ${key}`},signal:AbortSignal.timeout(15000)}));
        return (data.tracks || []).slice(0,40).map((t:Row)=>({...t,kind:"track",editable:false}));
      }catch{warnings.push("Recherche Ketvoice API temporairement indisponible.");return [];}
    })():Promise.resolve([])
  ]);
  const local:Row[]=kind==="track" ? uploads.map(t=>({...t,kind})) : [];
  if(kind!=="track") {
    const seen=new Set<string>();
    for(const row of uploads) {
      const title=String(kind==="album"?row.album:row.artist || "");
      const id=JSON.stringify([kind,kind==="album"?row.artist:"",title]);
      if(!title || seen.has(id))continue;seen.add(id);
      local.push({id,kind,title,artist:row.artist,artworkURL:row.artworkURL,source:"Mes publications",editable:row.editable,sourceId:row.id});
    }
  }
  const seen=new Set<string>();
  return {results:[...local,...yt,...broker].filter(r=>{if(seen.has(r.id))return false;seen.add(r.id);return true;}),warnings,
    scope:"Catalogue YouTube Music et Ketvoice API utilisés par l’app, plus les publications. Aucun résultat n’est une preuve de disponibilité du flux."};
}
