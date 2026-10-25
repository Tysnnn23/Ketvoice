
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
let handler;
globalThis.Deno={env:{get:k=>({SUPABASE_URL:'https://test.supabase.co',SUPABASE_SERVICE_ROLE_KEY:'test-only'})[k]},serve:h=>handler=h};
const id='11111111-1111-4111-8111-111111111111',owner='00000000-0000-4000-8000-000000000001';
const initial={id,title:'Owned audio',artist:'QA',album:'Test',duration_ms:1000,audio_path:`${id}/audio.mp3`,cover_path:`${id}/cover.jpg`,format:'mp3',bytes:2003,created_by:owner,published:true};
let row={...initial}, uploads=[],deletes=[],patches=[],failCover=false,failPatch=false,race=false;
globalThis.fetch=async(input,init={})=>{
 const url=new URL(String(input));
 if(url.pathname==='/auth/v1/user') {
  const token=new Headers(init.headers).get('authorization');
  if(token==='Bearer invalid')return new Response(null,{status:401});
  return Response.json({id:owner,email:token==='Bearer other'?'other@example.com':'konnektsupport@gmail.com',email_confirmed_at:'2026-10-23',is_anonymous:false});
 }
 if(url.pathname.startsWith('/storage/v1/object/')) {
  if(init.method==='DELETE'){deletes.push(JSON.parse(init.body).prefixes);return new Response(null,{status:204});}
  if(failCover&&url.pathname.includes('/cover.'))return new Response(null,{status:500});
  uploads.push(url.pathname);return Response.json({ok:true});
 }
 if(url.pathname==='/rest/v1/ketvoice_tracks') {
  if(init.method==='PATCH') {
   patches.push(JSON.parse(init.body));
   if(failPatch)throw new Error('Ambiguous network interruption');
   if(race)return Response.json([]);
   assert.equal(url.searchParams.get('created_by'),`eq.${owner}`);
   assert.equal(url.searchParams.get('audio_path'),`eq.${row.audio_path}`);
   Object.assign(row,JSON.parse(init.body));return Response.json([row]);
  }
  const match=url.searchParams.get('id')===`eq.${row.id}`&&url.searchParams.get('created_by')===`eq.${row.created_by}`;
  return Response.json(match?[row]:[]);
 }
 throw new Error('Unexpected fixture URL: '+url.pathname);
};
await import(pathToFileURL(process.argv[2]));
let tests=0;
function request(path,token='support',body){return new Request(`https://test.supabase.co/functions/v1/ketvoice-content${path}`,{method:body?'POST':'GET',headers:token?{'x-ketvoice-admin':token}:{},body});}
async function check(name,req,status){const res=await handler(req);const result=await res.json();assert.equal(res.status,status,name+': '+JSON.stringify(result));tests++;return result;}
async function editable(){return (await check('read owned upload',request(`/admin/song?id=${id}`),200)).revision;}
function edit(revision,{audio=false,cover=false,metadata={},extra=false}={}){
 const form=new FormData();form.set('metadata',JSON.stringify({trackId:`kv:upload:${id}`,revision,rightsConfirmed:true,durationMs:2000,...metadata}));
 if(audio)form.set('audio',new File([typeof audio==='string'?audio:'ID3'+'.'.repeat(2000)],'replacement.mp3'));
 if(cover)form.set('cover',new File([new Uint8Array([255,216,255,0,0])],'cover.jpg'));
 if(extra)form.set('created_by','spoof');
 return request('/admin/song/update','support',form);
}
function reset(){row={...initial};uploads=[];deletes=[];patches=[];failCover=false;failPatch=false;race=false;}
await check('anonymous denied',request(`/admin/song?id=${id}`,null),401);
await check('invalid token denied',request(`/admin/song?id=${id}`,'invalid'),401);
await check('wrong mailbox denied',request(`/admin/song?id=${id}`,'other'),403);
await check('catalog song cannot be edited',request('/admin/song?id=yt:abcdefghijk'),400);
row.created_by='other-owner';await check('ownership enforced',request(`/admin/song?id=${id}`),404);reset();
let revision=await editable();
await check('empty update denied',edit(revision),400);
await check('rights required',edit(revision,{cover:true,metadata:{rightsConfirmed:false}}),400);
await check('owner field injection denied',edit(revision,{cover:true,metadata:{created_by:'spoof'}}),400);
await check('form field injection denied',edit(revision,{cover:true,extra:true}),400);
await check('non-upload identity denied',edit(revision,{cover:true,metadata:{trackId:'yt:abcdefghijk'}}),400);
await check('fake MP3 denied before upload',edit(revision,{audio:'<html>not audio</html>'}),400);
assert.equal(uploads.length,0);
let result=await check('cover-only replacement',edit(revision,{cover:true}),200);
assert.equal(row.audio_path,initial.audio_path);assert.equal(row.duration_ms,1000);assert.equal(row.id,id);assert.notEqual(row.cover_path,initial.cover_path);assert.equal(deletes.length,0);
await check('stale revision denied',edit(revision,{audio:true}),409);
revision=result.revision;result=await check('audio-only replacement',edit(revision,{audio:true}),200);
assert.equal(row.duration_ms,2000);assert.notEqual(row.audio_path,initial.audio_path);assert.equal(result.track.id,`kv:upload:${id}`);
assert.ok(!patches.some(p=>'title'in p||'created_by'in p||'id'in p));
reset();revision=await editable();await check('audio and cover replace together',edit(revision,{audio:true,cover:true}),200);
assert.notEqual(row.audio_path,initial.audio_path);assert.notEqual(row.cover_path,initial.cover_path);
reset();revision=await editable();race=true;await check('concurrent edit conflicts atomically',edit(revision,{audio:true,cover:true}),409);
assert.equal(row.audio_path,initial.audio_path);assert.equal(deletes.flat().length,2);assert.ok(deletes.flat().every(p=>p!==initial.audio_path&&p!==initial.cover_path));
reset();revision=await editable();failCover=true;await check('failed second upload preserves old record',edit(revision,{audio:true,cover:true}),502);
assert.equal(patches.length,0);assert.equal(deletes.flat().length,1);assert.equal(row.audio_path,initial.audio_path);
reset();revision=await editable();failPatch=true;await check('ambiguous commit never deletes possibly committed media',edit(revision,{audio:true}),500);
assert.equal(deletes.length,0);
console.log(`${tests} imported-song editing checks passed; all network mocked.`);
