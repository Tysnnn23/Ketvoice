const ADMIN_EMAIL = "konnektsupport@gmail.com";
const BUCKET = "ketvoice-public-media";
const MAX_AUDIO = 25 * 1024 * 1024;
const MAX_COVER = 4 * 1024 * 1024;
const MAX_LYRICS = 1024 * 1024;
const CORS = {"access-control-allow-origin":"*", "access-control-allow-headers":"authorization,apikey,content-type,x-ketvoice-admin", "access-control-allow-methods":"GET,POST,OPTIONS"};
type Row = Record<string, unknown>;
class HttpError extends Error { constructor(public status: number, message: string) { super(message); } }
const base = () => Deno.env.get("SUPABASE_URL")!.replace(/\/$/, "");
const service = () => Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const headers = () => ({apikey:service(), Authorization:`Bearer ${service()}`, "Content-Type":"application/json"});
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {status,headers:{...CORS,"content-type":"application/json; charset=utf-8","cache-control":"no-store"}});
async function api(path: string, init: RequestInit = {}): Promise<Response> {
  return await fetch(base() + path, {...init, headers:{...headers(),...init.headers},signal:AbortSignal.timeout(60000)});
}
async function database(path: string, init: RequestInit = {}): Promise<Row[]> {
  const res = await api("/rest/v1/" + path, init);
  if (!res.ok) throw new HttpError(502,"Service de publication indisponible");

  const text = await res.text();
  return text.trim() ? JSON.parse(text) : [];
}
async function administrator(req: Request): Promise<string> {
  const token = req.headers.get("x-ketvoice-admin");
  if (!token || token.length > 12000) throw new HttpError(401,"Connexion administrateur requise");

  const res = await api("/auth/v1/user", {headers:{Authorization:`Bearer ${token}`}});
  if (!res.ok) throw new HttpError(401,"Session administrateur expirée");
  const user = await res.json();
  if (user.is_anonymous || !user.email_confirmed_at || String(user.email).toLowerCase() !== ADMIN_EMAIL)
    throw new HttpError(403,"Ce compte ne peut pas publier");
  return user.id;
}
async function bounded(req: Request, max: number): Promise<Uint8Array> {
  if (Number(req.headers.get("content-length") || 0) > max) throw new HttpError(413,"Fichier trop volumineux");
  const reader = req.body?.getReader();
  if (!reader) throw new HttpError(400,"Fichier vide");
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) { const {value,done} = await reader.read(); if(done) break;
      size += value.length;
      if (size > max) { await reader.cancel(); throw new HttpError(413,"Fichier trop volumineux"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let at = 0;
  for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
  return bytes;
}
function field(value: unknown, label: string, optional = false): string {
  const text = String(value ?? "").trim();
  if ((!optional && !text) || text.length > 200) throw new HttpError(400,`${label} invalide`);
  return text;
}
function duration(value: unknown): number {
  const number = Number(value ?? 0);
  if (!Number.isSafeInteger(number) || number < 0 || number > 86400000) throw new HttpError(400,"Durée invalide");
  return number;
}
function key(title: string, artist: string): string {
  return [title,artist].map(s=>s.normalize("NFD").replace(/\p{M}/gu,"").toLowerCase().replace(/[^\p{L}\p{N}]+/gu," ").trim()).join("|");
}
function lyrics(content: unknown, format: unknown): {content:string,format:string} {
  const text = String(content ?? "").replace(/^\uFEFF/, "");
  const type = String(format ?? "").toLowerCase();
  if (!text.trim() || new TextEncoder().encode(text).length > MAX_LYRICS || text.includes("\0")) throw new HttpError(400,"Paroles invalides ou trop volumineuses");
  if (type === "ttml") {
    if (/<!DOCTYPE|<!ENTITY|<script\b/i.test(text) || !/<(?:\w+:)?tt\b/.test(text) || !/<(?:\w+:)?p\b[^>]*\bbegin\s*=/.test(text))
      throw new HttpError(400,"Fichier TTML invalide");
  } else if (type !== "lrc" || !/\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]/.test(text)) throw new HttpError(400,"Fichier LRC invalide");
  return {content:text,format:type};
}
function lyricRow(meta: Row, source: {content:string,format:string}, owner: string): Row {
  const title = field(meta.title,"Titre"), artist = field(meta.artist,"Artiste");
  const trackId = String(meta.trackId ?? "");
  if (!trackId || trackId.length > 180) throw new HttpError(400,"Identifiant du titre invalide");
  const isrc = String(meta.isrc ?? "").toUpperCase();
  if (isrc && !/^[A-Z]{2}[A-Z0-9]{3}\d{7}$/.test(isrc)) throw new HttpError(400,"ISRC invalide");
  return {track_id:trackId,recording_key:key(title,artist),title,artist,duration_ms:duration(meta.durationMs),isrc:isrc || null,...source,created_by:owner,published:true};
}
const publicFile = (path: string) => `${base()}/storage/v1/object/public/${BUCKET}/${path}`;
function track(row: Row): Row {
  return {id:`kv:upload:${row.id}`,addonId:row.id,source:"upload",title:row.title,artist:row.artist,album:row.album,
    durationMs:row.duration_ms,artworkURL:row.cover_path ? publicFile(String(row.cover_path)) : null,
    streamURL:publicFile(String(row.audio_path)),format:row.format};
}
async function store(path: string, data: Uint8Array, mime: string): Promise<void> {
  const res = await api(`/storage/v1/object/${BUCKET}/${path}`, {method:"POST",headers:{"Content-Type":mime,"Cache-Control":"31536000","x-upsert":"false"},body:data});
  if(!res.ok) throw new HttpError(502,"Impossible de sauvegarder le fichier");
}
async function readAudio(file: FormDataEntryValue | null) {
  if (!(file instanceof File) || !file.size || file.size > MAX_AUDIO) throw new HttpError(400,"Audio requis (25 Mo maximum)");
  const ext=file.name.toLowerCase().split('.').pop() || "";
  const mimes: Record<string,string>={mp3:"audio/mpeg",m4a:"audio/mp4",aac:"audio/aac",flac:"audio/flac",wav:"audio/wav",ogg:"audio/ogg",opus:"audio/ogg"};
  const data=new Uint8Array(await file.arrayBuffer());
  const ascii=(a:number,b:number)=>new TextDecoder().decode(data.slice(a,b));
  const frame=data[0]===255 && (data[1]&224)===224;
  const valid=ext==="mp3" ? (ascii(0,3)==="ID3" || frame) : ext==="m4a" ? ascii(4,8)==="ftyp" : ext==="aac" ? frame : ext==="flac" ? ascii(0,4)==="fLaC" : ext==="wav" ? ascii(0,4)==="RIFF" && ascii(8,12)==="WAVE" : ["ogg","opus"].includes(ext) && ascii(0,4)==="OggS";
  if(!mimes[ext] || !valid) throw new HttpError(400,"Format audio non pris en charge");
  return {data,ext,mime:mimes[ext],size:file.size};
}
async function readCover(file: FormDataEntryValue | null) {
  if(file === null) return null;
  if(!(file instanceof File) || !file.size || file.size>MAX_COVER) throw new HttpError(400,"Pochette trop volumineuse ou vide (4 Mo maximum)");
  const data=new Uint8Array(await file.arrayBuffer()), h=new TextDecoder().decode(data.slice(0,12));
  if(data[0]===255 && data[1]===216 && data[2]===255) return {data,ext:"jpg",mime:"image/jpeg"};
  if(data[0]===137 && h.slice(1,4)==="PNG") return {data,ext:"png",mime:"image/png"};
  if(h.startsWith("RIFF") && h.slice(8,12)==="WEBP") return {data,ext:"webp",mime:"image/webp"};
  throw new HttpError(400,"Pochette JPEG, PNG ou WebP requise");
}
function uploadId(value: unknown): string {
  const id=String(value ?? "").replace(/^kv:upload:/, "").toLowerCase();
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id))
    throw new HttpError(400,"Seuls les titres importés peuvent être modifiés");
  return id;
}
async function ownedSong(id: string, owner: string): Promise<Row> {
  const rows=await database(`ketvoice_tracks?id=eq.${id}&created_by=eq.${encodeURIComponent(owner)}&published=eq.true&limit=1`);
  if(!rows.length || rows[0].created_by!==owner || rows[0].id!==id) throw new HttpError(404,"Titre importé introuvable pour ce compte");
  return rows[0];
}
async function revision(row: Row): Promise<string> {
  const bytes=new TextEncoder().encode(JSON.stringify([row.audio_path,row.cover_path,row.duration_ms]));
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256",bytes))].map(n=>n.toString(16).padStart(2,"0")).join("");
}
async function updateSong(req: Request, owner: string): Promise<Response> {
  const bytes=await bounded(req,MAX_AUDIO+MAX_COVER+65536);
  const form=await new Request(req.url,{method:"POST",headers:{"content-type":req.headers.get("content-type") || ""},body:bytes}).formData();
  if([...form.keys()].some(k=>!["metadata","audio","cover"].includes(k)) || ["metadata","audio","cover"].some(k=>form.getAll(k).length>1))
    throw new HttpError(400,"Champs de modification invalides");
  let meta: Row;
  try { meta=JSON.parse(String(form.get("metadata") || "{}")); }
  catch { throw new HttpError(400,"Métadonnées invalides"); }
  if(!meta || Array.isArray(meta) || typeof meta!=="object" || Object.keys(meta).some(k=>!["trackId","revision","durationMs","rightsConfirmed"].includes(k)))
    throw new HttpError(400,"Métadonnées invalides");
  if(meta.rightsConfirmed!==true) throw new HttpError(400,"Confirmez vos droits de publication");
  const id=uploadId(meta.trackId), old=await ownedSong(id,owner);
  if(meta.revision!==await revision(old)) throw new HttpError(409,"Ce titre a changé. Rouvrez sa modification.");
  if(!form.has("audio") && !form.has("cover")) throw new HttpError(400,"Choisissez un nouvel audio ou une nouvelle pochette");
  const audio=form.has("audio") ? await readAudio(form.get("audio")) : null;
  const image=await readCover(form.get("cover"));
  const update: Row={};
  if(audio) { update.duration_ms=duration(meta.durationMs); update.format=audio.ext; update.bytes=audio.size; }
  const prefix=`${id}/${crypto.randomUUID()}`, uploaded:string[]=[];
  let patchAttempted=false, definitelyUncommitted=false;
  try {
    if(audio) { const path=`${prefix}/audio.${audio.ext}`;await store(path,audio.data,audio.mime);uploaded.push(path);update.audio_path=path; }
    if(image) { const path=`${prefix}/cover.${image.ext}`;await store(path,image.data,image.mime);uploaded.push(path);update.cover_path=path; }
    const coverFilter=old.cover_path ? `eq.${encodeURIComponent(String(old.cover_path))}` : "is.null";
    patchAttempted=true;
    const result=await database(`ketvoice_tracks?id=eq.${id}&created_by=eq.${encodeURIComponent(owner)}&published=eq.true&audio_path=eq.${encodeURIComponent(String(old.audio_path))}&cover_path=${coverFilter}`,
      {method:"PATCH",headers:{Prefer:"return=representation"},body:JSON.stringify(update)});
    if(!result.length) { definitelyUncommitted=true;throw new HttpError(409,"Ce titre a changé. Rouvrez sa modification."); }

    return json({published:true,track:track(result[0]),revision:await revision(result[0])});
  } catch(error) {
    if(uploaded.length && (!patchAttempted || definitelyUncommitted))
      await api(`/storage/v1/object/${BUCKET}`,{method:"DELETE",body:JSON.stringify({prefixes:uploaded})}).catch(()=>{});
    throw error;
  }
}
async function publishSong(req: Request, owner: string): Promise<Response> {
  const bytes = await bounded(req, MAX_AUDIO + MAX_COVER + MAX_LYRICS + 65536);
  const form = await new Request(req.url,{method:"POST",headers:{"content-type":req.headers.get("content-type") || ""},body:bytes}).formData();
  const meta = JSON.parse(String(form.get("metadata") || "{}"));
  if (meta.rightsConfirmed !== true) throw new HttpError(400,"Confirmez vos droits de publication");
  const title=field(meta.title,"Titre"), artist=field(meta.artist,"Artiste"), album=field(meta.album,"Album",true);
  const durationMs=duration(meta.durationMs);
  const audio=form.get("audio"), cover=form.get("cover"), lyric=form.get("lyrics");
  if (!(audio instanceof File) || !audio.size || audio.size > MAX_AUDIO) throw new HttpError(400,"Audio requis (25 Mo maximum)");
  const ext=audio.name.toLowerCase().split('.').pop() || "";
  const mimes: Record<string,string>={mp3:"audio/mpeg",m4a:"audio/mp4",aac:"audio/aac",flac:"audio/flac",wav:"audio/wav",ogg:"audio/ogg",opus:"audio/ogg"};
  const data=new Uint8Array(await audio.arrayBuffer());
  const ascii=(a:number,b:number)=>new TextDecoder().decode(data.slice(a,b));
  const frame=data[0]===255 && (data[1]&224)===224;
  const valid=ext==="mp3" ? (ascii(0,3)==="ID3" || frame) : ext==="m4a" ? ascii(4,8)==="ftyp" : ext==="aac" ? frame : ext==="flac" ? ascii(0,4)==="fLaC" : ext==="wav" ? ascii(0,4)==="RIFF" && ascii(8,12)==="WAVE" : ["ogg","opus"].includes(ext) && ascii(0,4)==="OggS";
  if(!mimes[ext] || !valid) throw new HttpError(400,"Format audio non pris en charge");
  let image:Uint8Array|null=null, imageExt="", imageMime="";
  if(cover instanceof File && cover.size) {
    if(cover.size>MAX_COVER) throw new HttpError(400,"Pochette trop volumineuse (4 Mo maximum)");
    image=new Uint8Array(await cover.arrayBuffer());
    const h=new TextDecoder().decode(image.slice(0,12));
    if(image[0]===255 && image[1]===216 && image[2]===255) {imageExt="jpg";imageMime="image/jpeg";}
    else if(image[0]===137 && h.slice(1,4)==="PNG") {imageExt="png";imageMime="image/png";}
    else if(h.startsWith("RIFF") && h.slice(8,12)==="WEBP") {imageExt="webp";imageMime="image/webp";}
    else throw new HttpError(400,"Pochette JPEG, PNG ou WebP requise");
  }
  let words:{content:string,format:string}|null=null;
  if(lyric instanceof File && lyric.size) {
    if(lyric.size>MAX_LYRICS) throw new HttpError(400,"Paroles trop volumineuses");
    words=lyrics(await lyric.text(),lyric.name.split('.').pop());
  }
  const id=crypto.randomUUID(), audioPath=`${id}/audio.${ext}`, coverPath=image ? `${id}/cover.${imageExt}` : null;
  const uploaded:string[]=[];
  try {
    await store(audioPath,data,mimes[ext]); uploaded.push(audioPath);
    if(image && coverPath) {await store(coverPath,image,imageMime);uploaded.push(coverPath);}
    const rows=await database("ketvoice_tracks",{method:"POST",headers:{Prefer:"return=representation"},body:JSON.stringify({id,title,artist,album,duration_ms:durationMs,audio_path:audioPath,cover_path:coverPath,format:ext,bytes:audio.size,created_by:owner,published:true})});
    if(words) await database("ketvoice_lyrics",{method:"POST",body:JSON.stringify(lyricRow({trackId:`kv:upload:${id}`,title,artist,durationMs},words,owner))});
    return json({track:track(rows[0]),published:true},201);
  } catch(error) {
    await api(`/rest/v1/ketvoice_tracks?id=eq.${id}`,{method:"DELETE"});
    if(uploaded.length) await api(`/storage/v1/object/${BUCKET}`,{method:"DELETE",body:JSON.stringify({prefixes:uploaded})});
    throw error;
  }
}
async function publicLyrics(url: URL): Promise<Response> {
  const id=url.searchParams.get("trackId") || "", title=url.searchParams.get("title") || "", artist=url.searchParams.get("artist") || "";
  const select="select=id,track_id,title,artist,format,content,duration_ms,created_at&published=eq.true&order=created_at.desc&limit=10";
  let rows:Row[]=[];
  if(id && id.length<=180) rows=await database(`ketvoice_lyrics?${select}&track_id=eq.${encodeURIComponent(id)}`);
  const isrc=url.searchParams.get("isrc") || "";
  if(!rows.length && /^[A-Z]{2}[A-Z0-9]{3}\d{7}$/.test(isrc)) rows=await database(`ketvoice_lyrics?${select}&isrc=eq.${isrc}`);
  if(!rows.length && title && artist && title.length<=200 && artist.length<=200) {
    rows=await database(`ketvoice_lyrics?${select}&recording_key=eq.${encodeURIComponent(key(title,artist))}`);
    const wanted=Number(url.searchParams.get("durationMs") || 0);
    rows=rows.filter(r=>!wanted || !r.duration_ms || Math.abs(Number(r.duration_ms)-wanted)<8000);
  }
  return json({source:"Ketvoice API",versions:rows});
}
Deno.serve(async(req:Request)=>{
  if(req.method==="OPTIONS") return new Response(null,{status:204,headers:CORS});
  const url=new URL(req.url), path=(url.pathname.split("/ketvoice-content")[1] || "/").replace(/\/+$/,"") || "/";
  try {
    if(req.method==="GET" && path==="/health") return json({ok:true,name:"Ketvoice API · Publications"});
    if(req.method==="GET" && path==="/track") {
      const id=url.searchParams.get("id") || "";
      if(!/^[0-9a-f-]{36}$/.test(id)) throw new HttpError(400,"Identifiant du titre invalide");
      const rows=await database(`ketvoice_tracks?id=eq.${id}&published=eq.true&limit=1`);
      return rows.length ? json({track:track(rows[0])}) : json({error:"Titre indisponible"},404);
    }
    if(req.method==="GET" && path==="/search") {
      const q=(url.searchParams.get("q") || "").trim();
      const rows=q.length>=2 && q.length<=160 ? await database("rpc/ketvoice_search_catalog",{method:"POST",body:JSON.stringify({p_query:q})}) : [];
      return json({tracks:rows.map(track)});
    }
    if(req.method==="GET" && path==="/lyrics") return await publicLyrics(url);
    if(path.startsWith("/admin/")) {
      const owner=await administrator(req);
      if(req.method==="GET" && path==="/admin/session") return json({allowed:true});
      if(req.method==="GET" && path==="/admin/song") {
        const row=await ownedSong(uploadId(url.searchParams.get("id")),owner);
        return json({track:track(row),revision:await revision(row)});
      }
      if(req.method==="POST" && path==="/admin/song/update") return await updateSong(req,owner);
      if(req.method==="POST" && path==="/admin/song") return await publishSong(req,owner);
      if(req.method==="POST" && path==="/admin/lyrics") {
        const data=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await bounded(req,MAX_LYRICS+8192)));
        if(data.rightsConfirmed!==true) throw new HttpError(400,"Confirmez vos droits de publication");
        const row=lyricRow(data,lyrics(data.content,data.format),owner);
        const result=await database("ketvoice_lyrics",{method:"POST",headers:{Prefer:"return=representation"},body:JSON.stringify(row)});
        return json({published:true,id:result[0].id,source:"Ketvoice API"},201);
      }
    }
    return json({error:"Route inconnue"},404);
  } catch(error) {
    if(error instanceof HttpError) return json({error:error.message},error.status);
    console.error("ketvoice-content",error instanceof Error ? error.name : "unknown");
    return json({error:"Publication impossible. Vérifiez les fichiers et réessayez."},500);
  }
});
