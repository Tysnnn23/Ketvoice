import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';

let handler,stored,deleted=[],writes=[],patches=[];
const owner='00000000-0000-4000-8000-000000000001';
const id='11111111-1111-4111-8111-111111111111',target='22222222-2222-4222-8222-222222222222';
const source={id,title:'First song',artist:'Singer',album:'Album',duration_ms:2500,audio_path:`${id}/audio.mp3`,cover_path:`${id}/cover.jpg`,created_by:owner,published:true,format:'mp3'};
let rows=[source,{...source,id:target,title:'Other song',artist:'Other',album:'Different',cover_path:null}];
let pages=null,offsets=[];
globalThis.Deno={env:{get:key=>({SUPABASE_URL:'https://test.supabase.co',SUPABASE_SERVICE_ROLE_KEY:'test-only'})[key]},serve:value=>handler=value};
globalThis.fetch=async(input,init={})=>{
  const url=new URL(String(input));
  if(url.pathname==='/auth/v1/user'){
    const token=new Headers(init.headers).get('authorization');
    return Response.json({id:owner,email:token==='Bearer other'?'other@example.com':'konnektsupport@gmail.com',is_anonymous:token==='Bearer anonymous',email_confirmed_at:token==='Bearer unconfirmed'?null:'2026-10-27'});
  }
  if(url.pathname.startsWith('/storage/v1/object/')){
    if(init.method==='DELETE')deleted.push(...JSON.parse(init.body).prefixes);
    else writes.push(url.pathname);
    return Response.json({ok:true});
  }
  if(url.pathname==='/rest/v1/ketvoice_tracks'){
    if(init.method==='POST'){stored=JSON.parse(init.body);return Response.json([stored]);}
    if(init.method==='PATCH'){
      patches.push(JSON.parse(init.body));const row=rows.find(row=>url.searchParams.get('id')===`eq.${row.id}`);Object.assign(row,patches.at(-1));return Response.json([row]);
    }
    assert.equal(url.searchParams.get('created_by'),`eq.${owner}`);
    assert.equal(url.searchParams.get('published'),'eq.true');
    if(url.searchParams.has('offset')){
      assert.equal(url.searchParams.get('order'),'created_at.desc,id.asc');
      const offset=Number(url.searchParams.get('offset'));offsets.push(offset);
      return Response.json(pages?pages.slice(offset,offset+250):rows.filter(row=>row.created_by===owner&&row.published&&row.album));
    }
    return Response.json(rows.filter(row=>url.searchParams.get('id')===`eq.${row.id}`&&row.created_by===owner&&row.published));
  }
  throw Error('Unexpected fixture URL '+url.pathname);
};
await import(pathToFileURL(process.argv[2]));
let checks=0;
function request(path,body,token='support'){return new Request('https://test.supabase.co/functions/v1/ketvoice-content'+path,{method:body?'POST':'GET',headers:token?{'x-ketvoice-admin':token}:{},body});}
async function check(name,req,status){const res=await handler(req),body=await res.json();assert.equal(res.status,status,name+': '+JSON.stringify(body));checks++;return body;}
function song(meta={},cover=false,path='/admin/song'){
  const data=new FormData();data.set('metadata',JSON.stringify({title:'New song',artist:'Spoofed artist',album:'Spoofed album',durationMs:2345,rightsConfirmed:true,...meta}));
  if(path==='/admin/song')data.set('audio',new File(['ID3demo'],'audio.mp3'));
  if(cover)data.set('cover',new File([new Uint8Array([255,216,255,0])],'cover.jpg'));
  return request(path,data);
}
await check('anonymous album listing denied',request('/admin/albums',null,null),401);
for(const token of ['other','anonymous','unconfirmed'])await check(token+' denied',request('/admin/albums',null,token),403);
let result=await check('owner albums',request('/admin/albums'),200);
assert.equal(result.albums.length,2);const album=result.albums.find(album=>album.artist==='Singer');
assert.equal(album.trackCount,1);assert.equal(album.sourceId,`kv:upload:${id}`);assert.ok(album.artworkURL.endsWith('/'+source.cover_path));
assert.ok(!('streamURL' in album));
const meta={albumSourceId:album.sourceId,albumRevision:album.revision};
await check('new song inherits verified album',song(meta),201);
assert.equal(stored.artist,source.artist);assert.equal(stored.album,source.album);assert.equal(stored.cover_path,source.cover_path);assert.equal(stored.duration_ms,2345);
assert.equal(writes.length,1,'only new audio uploaded');assert.equal(deleted.length,0);
await check('explicit cover overrides inherited cover',song(meta,true),201);assert.notEqual(stored.cover_path,source.cover_path);
await check('stale album revision denied',song({...meta,albumRevision:'stale'}),409);
await check('revision required',song({albumSourceId:album.sourceId}),409);
await check('general catalog source denied',song({...meta,albumSourceId:'yt:abcdefghijk'}),400);
await check('unknown source denied',song({...meta,albumSourceId:'33333333-3333-4333-8333-333333333333'}),404);
source.created_by='someone-else';await check('other owner source denied',song(meta),404);source.created_by=owner;
source.published=false;await check('unpublished source denied',song(meta),404);source.published=true;
source.album='';await check('non-album source denied',song(meta),400);source.album='Album';
source.cover_path=null;
result=await check('album without cover',request('/admin/albums'),200);
const coverless=result.albums.find(album=>album.artist==='Singer');assert.equal(coverless.artworkURL,null);
await check('new song can inherit coverless album',song({albumSourceId:coverless.sourceId,albumRevision:coverless.revision}),201);assert.equal(stored.cover_path,null);
source.cover_path=`${id}/cover.jpg`;
const edit=await check('load target',request('/admin/song?id='+target),200);
await check('move owned song into selected album',song({...meta,trackId:`kv:upload:${target}`,revision:edit.revision},false,'/admin/song/update'),200);
assert.equal(rows[1].album,source.album);assert.equal(rows[1].artist,source.artist);assert.equal(rows[1].cover_path,source.cover_path);
assert.ok(!('audio_path' in patches.at(-1)));assert.equal(deleted.length,0,'inherited cover is never deleted');
// More than one API page, with a cover present only on the last duplicate.
pages=Array.from({length:251},(_,i)=>({...source,id:i===250?target:id,cover_path:i===250?source.cover_path:null}));offsets=[];
result=await check('album grouping across pages',request('/admin/albums'),200);
assert.deepEqual(offsets,[0,250]);assert.equal(result.albums.length,1);assert.equal(result.albums[0].trackCount,251);assert.equal(result.albums[0].sourceId,`kv:upload:${target}`);
console.log(`${checks} studio album API checks passed; all network/Storage mocked.`);
