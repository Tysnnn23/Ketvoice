
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

let handler;
globalThis.Deno = {env:{get:key=>({SUPABASE_URL:'https://test.supabase.co',SUPABASE_SERVICE_ROLE_KEY:'test-server-only'})[key]},serve:value=>handler=value};
const writes=[];
const deletes=[];
let storedTrack=null;
let minimalStatus=201, failLyrics=false;
globalThis.fetch=async(input,init={})=>{
  const url=String(input), headers=new Headers(init.headers);
  if(url.endsWith('/auth/v1/user')) {
    const token=headers.get('authorization')?.replace('Bearer ','');
    if(!['support','other','unconfirmed','anonymous'].includes(token)) return Response.json({error:'invalid'}, {status:401});
    return Response.json({id:'00000000-0000-0000-0000-000000000001',
      email:token==='other'?'other@example.com':'konnektsupport@gmail.com',
      email_confirmed_at:token==='unconfirmed'?null:'2026-10-22',
      user_metadata:{email:'konnektsupport@gmail.com'}, is_anonymous:token==='anonymous'});
  }
  if(url.includes('/storage/v1/object/') && init.method==='POST') {writes.push(url);return Response.json({Key:'ok'});}
  if(init.method==='DELETE') {deletes.push(url);return new Response(null,{status:204});}
  if(url.endsWith('/rest/v1/ketvoice_tracks') && init.method==='POST') {
    storedTrack=JSON.parse(init.body);return Response.json([storedTrack],{status:201});
  }
  if(url.endsWith('/rest/v1/ketvoice_lyrics') && init.method==='POST') {
    if(failLyrics) return Response.json({error:'fixture insert failure'}, {status:500});
    writes.push(JSON.parse(init.body));

    return headers.get('prefer')==='return=representation' ? Response.json([{id:'revision-id'}],{status:201}) : new Response(null,{status:minimalStatus});
  }
  if(url.includes('/rest/v1/')) return Response.json([]);
  throw new Error('Unexpected request: '+url);
};
await import(pathToFileURL(process.argv[2]));
const request=(path,token,body)=>new Request('https://test.supabase.co/functions/v1/ketvoice-content'+path,{
  method:body?'POST':'GET',headers:token?{'x-ketvoice-admin':token}: {},body:body?JSON.stringify(body):undefined});
let tests=0;
async function status(name,req,expected){const r=await handler(req);assert.equal(r.status,expected,name+': '+await r.text());tests++;}
await status('anonymous cannot publish',request('/admin/lyrics',null,{email:'konnektsupport@gmail.com'}),401);
await status('forged token rejected',request('/admin/session','invalid'),401);
await status('different real email cannot spoof metadata',request('/admin/session','other'),403);
await status('unconfirmed support email rejected',request('/admin/session','unconfirmed'),403);
await status('anonymous account rejected',request('/admin/session','anonymous'),403);
await status('verified support allowed',request('/admin/session','support'),200);
const data={trackId:'yt:abcdefghijk',title:'Owned demo',artist:'Test artist',durationMs:30000,rightsConfirmed:true,format:'lrc',content:'[00:01.00]Test lyric'};
await status('public lyrics revision',request('/admin/lyrics','support',data),201);
assert.equal(writes.at(-1).content,data.content);
await status('rights confirmation required',request('/admin/lyrics','support',{...data,rightsConfirmed:false}),400);
await status('XXE rejected',request('/admin/lyrics','support',{...data,format:'ttml',content:'<!DOCTYPE tt [<!ENTITY x SYSTEM "file:///secret">]><tt><p begin="1s">&x;</p></tt>'}),400);
await status('plain text pretending to be LRC rejected',request('/admin/lyrics','support',{...data,content:'not timed'}),400);
await status('public search needs no admin',request('/search?q=test'),200);
await status('public lyrics need no admin',request('/lyrics?trackId=yt:abcdefghijk'),200);
await status('malformed track id rejected',request('/track?id=../../etc'),400);
await status('unknown published track is 404',request('/track?id=00000000-0000-0000-0000-000000000001'),404);
function song(bytes,rights=true,withLyrics=true,withCover=false){
  const form=new FormData();form.set('metadata',JSON.stringify({title:'Owned demo',artist:'Test artist',durationMs:1000,rightsConfirmed:rights}));
  form.set('audio',new File([bytes],'audio.mp3',{type:'audio/mpeg'}));
  if(withLyrics) form.set('lyrics',new File(['[00:00.50]Test lyric'],'words.lrc',{type:'text/plain'}));
  if(withCover) form.set('cover',new File([new Uint8Array([255,216,255,0,0])],'cover.jpg',{type:'image/jpeg'}));
  return new Request('https://test.supabase.co/functions/v1/ketvoice-content/admin/song',{method:'POST',headers:{'x-ketvoice-admin':'support'},body:form});
}
await status('HTML is not MP3',song('<html>not audio</html>'),400);
await status('song rights confirmation required',song('ID3'+'.'.repeat(2000),false),400);
await status('audio and lyric sidecar publication',song('ID3'+'.'.repeat(2000)),201);
assert.equal(storedTrack.format,'mp3');assert.equal(storedTrack.bytes,2003);
assert.equal(storedTrack.created_by,'00000000-0000-0000-0000-000000000001');
await status('audio without sidecar',song('ID3'+'.'.repeat(2000),true,false),201);
await status('audio cover and minimal 201 lyric response',song('ID3'+'.'.repeat(2000),true,true,true),201);
assert.equal(deletes.length,0,'successful uploads must not trigger cleanup');
minimalStatus=204;
await status('legacy empty 204 response also accepted',song('ID3'+'.'.repeat(2000)),201);
minimalStatus=201;failLyrics=true;
await status('actual lyric DB failure still rolls back media',song('ID3'+'.'.repeat(2000),true,true,true),502);
assert.equal(deletes.length,2,'rollback deletes the track and its media objects');
console.log(`${tests} content API checks passed; all network and Storage calls mocked.`);
