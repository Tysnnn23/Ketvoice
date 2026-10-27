import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';

// Real browser media metadata; mocked owner authentication and publication API.
export default async function run(page) {
  const studio='https://tysnnn23.github.io/Ketvoice/admin/ketvoice-admin.html';
  const html=await readFile('admin/ketvoice-admin.html','utf8');
  const id='kv:upload:11111111-1111-4111-8111-111111111111';
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
  const albums=[{sourceId:id,revision:'album-revision',artist:'Chanteuse A',album:'Horizon',artworkURL:'https://xodklrunqlzyeiqtovgx.supabase.co/cover.png',trackCount:2},
    {sourceId:'kv:upload:22222222-2222-4222-8222-222222222222',revision:'other-revision',artist:'Chanteur B',album:'Horizon',artworkURL:null,trackCount:1}];
  let track={id,title:'Titre existant',artist:'Ancien artiste',album:'Ancien album',durationMs:45000},revision='edit-revision';
  const posts=[],errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.route(studio+'*',route=>route.fulfill({status:200,contentType:'text/html',body:html}));
  await page.route('https://xodklrunqlzyeiqtovgx.supabase.co/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;
    if(path==='/cover.png')return route.fulfill({status:200,contentType:'image/png',body:png});
    let result={};
    if(path.endsWith('/admin/session'))result={allowed:true};
    if(path.endsWith('/admin/albums'))result={albums};
    if(path.endsWith('/search'))result={tracks:[track]};
    if(path.endsWith('/admin/song')&&request.method()==='GET')result={track,revision};
    if(request.method()==='POST'&&(path.endsWith('/admin/song')||path.endsWith('/admin/song/update'))){
      const data=await new Request(request.url(),{method:'POST',headers:{'content-type':request.headers()['content-type']},body:request.postDataBuffer()}).formData();
      const meta=JSON.parse(data.get('metadata'));posts.push({path,meta,audio:data.get('audio')?.name,cover:data.has('cover')});
      const album=albums.find(album=>album.sourceId===meta.albumSourceId);
      track={...track,...meta,...(album?{artist:album.artist,album:album.album}:{}),id};revision='updated-revision';result={published:true,track,revision};
    }
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(result)});
  });
  await page.goto(studio);
  assert.equal(await page.locator('#navigation').isVisible(),false);
  await page.goto(studio+'?qa=auth#access_token=mock-access&refresh_token=mock-refresh&token_type=bearer&expires_in=3600');
  await page.waitForFunction(()=>document.querySelector('#newAlbumArtist').options.length===3);
  await page.locator('#newAlbumArtist').selectOption('Chanteuse A');
  assert.equal(await page.locator('#newAlbumSource option').count(),2);
  await page.locator('#newArtist').fill('Saisie personnelle');await page.locator('#newAlbum').fill('Brouillon');
  await page.locator('#newAlbumSource').selectOption(id);
  assert.equal(await page.locator('#newArtist').inputValue(),'Chanteuse A');assert.equal(await page.locator('#newAlbum').inputValue(),'Horizon');
  assert.equal(await page.locator('#newArtist').getAttribute('readonly'),'');
  await page.locator('#newAlbumPreview img').waitFor({state:'visible'});
  assert.equal(await page.locator('#newAlbumPreview img').evaluate(img=>img.complete&&img.naturalWidth>0),true);
  await page.locator('#newAlbumSource').selectOption('');
  assert.equal(await page.locator('#newArtist').inputValue(),'Saisie personnelle');assert.equal(await page.locator('#newAlbum').inputValue(),'Brouillon');
  await page.locator('#newAlbumSource').selectOption(id);
  function wav(seconds){
    const rate=8000,size=Math.round(rate*seconds)*2,b=Buffer.alloc(44+size);
    b.write('RIFF');b.writeUInt32LE(36+size,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(rate,24);b.writeUInt32LE(rate*2,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(size,40);return b;
  }
  await page.evaluate(()=>{
    // Instrument the page's main world (Patchright evaluate can be isolated).
    const script=document.createElement('script');
    script.textContent=`(()=>{const counts={created:0,revoked:0},create=URL.createObjectURL.bind(URL),revoke=URL.revokeObjectURL.bind(URL);const save=()=>document.documentElement.dataset.qaBlobs=JSON.stringify(counts);URL.createObjectURL=value=>{counts.created++;save();return create(value);};URL.revokeObjectURL=value=>{counts.revoked++;save();return revoke(value);};save();})();`;
    document.head.append(script);script.remove();
  });
  const audio=(seconds,name='sample.wav')=>({name,mimeType:'audio/wav',buffer:wav(seconds)});
  await page.locator('#newAudio').setInputFiles(audio(1.25));
  await page.waitForFunction(()=>document.querySelector('#newDuration').value==='1.25');
  await page.locator('#newAudio').setInputFiles(audio(3));
  await page.locator('#newAudio').setInputFiles(audio(2.5));
  await page.waitForFunction(()=>document.querySelector('#newDuration').value==='2.5');
  await page.locator('#newTitle').fill('Nouveau morceau');await page.locator('#newRights').check();
  await mkdir('release-assets/studio',{recursive:true});
  await page.screenshot({path:'release-assets/studio/albums-desktop.png',fullPage:true});
  await page.locator('#publishForm button.primary').click();
  await page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Musique publiée.'));
  assert.equal(posts.length,1);assert.equal(posts[0].meta.durationMs,2500);assert.equal(posts[0].meta.albumSourceId,id);assert.equal(posts[0].meta.albumRevision,'album-revision');assert.equal(posts[0].cover,false);
  assert.equal(await page.locator('#newDuration').inputValue(),'');assert.equal(await page.locator('#newArtist').inputValue(),'');assert.equal(await page.locator('#newArtist').getAttribute('readonly'),null);
  await page.locator('#newAlbumArtist').selectOption('Chanteur B');await page.locator('#newAlbumSource').selectOption(albums[1].sourceId);
  assert.equal(await page.locator('#newArtist').inputValue(),'Chanteur B');assert.equal(await page.locator('#newAlbumPreview img').count(),0);
  await page.locator('#newAudio').setInputFiles({name:'broken.mp3',mimeType:'audio/mpeg',buffer:Buffer.from('not audio')});
  await page.waitForFunction(()=>document.querySelector('#newDurationHint').textContent.includes('Saisissez-la manuellement'));
  assert.equal(await page.locator('#newDuration').inputValue(),'');await page.locator('#newDuration').fill('12.345');
  await page.getByRole('button',{name:/02.*Modifier/}).click();await page.locator('#search').fill('Titre');await page.locator('#searchButton').click();
  await page.getByRole('button',{name:'Modifier mon import'}).click();await page.locator('#editForm').waitFor({state:'visible'});
  await page.locator('#editAlbumArtist').selectOption('Chanteuse A');await page.locator('#editAlbumSource').selectOption(id);
  await page.locator('#editAudio').setInputFiles(audio(1.25));await page.waitForFunction(()=>document.querySelector('#editDuration').value==='1.25');
  // Reopening an editor must not retain a previous replacement file or rights.
  await page.locator('#editRights').check();await page.getByRole('button',{name:'Modifier mon import'}).click();
  assert.equal(await page.locator('#editAudio').evaluate(input=>input.files.length),0);assert.equal(await page.locator('#editRights').isChecked(),false);
  await page.locator('#editAlbumArtist').selectOption('Chanteuse A');await page.locator('#editAlbumSource').selectOption(id);
  await page.locator('#editAudio').setInputFiles(audio(1.25));await page.waitForFunction(()=>document.querySelector('#editDuration').value==='1.25');
  await page.locator('#editCover').setInputFiles({name:'cover.png',mimeType:'image/png',buffer:png});await page.locator('#editRights').check();
  await page.locator('#editForm button.primary').click();await page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Musique modifiée.'));
  assert.equal(posts.length,2);assert.equal(posts[1].meta.durationMs,1250);assert.equal(posts[1].meta.albumSourceId,id);assert.equal(posts[1].cover,true);
  await page.getByRole('button',{name:/01.*Ajouter/}).click();
  await page.locator('#newAlbumArtist').selectOption('Chanteuse A');await page.locator('#newAlbumSource').selectOption(id);
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:'release-assets/studio/albums-mobile.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'no horizontal overflow at 390px');
  const blobs=await page.evaluate(()=>JSON.parse(document.documentElement.dataset.qaBlobs));assert.ok(blobs.created>=6);assert.equal(blobs.created,blobs.revoked,'all media object URLs released');assert.deepEqual(errors,[]);
  return {realBrowserAudioMetadata:true,creationDurationMs:posts[0].meta.durationMs,replacementDurationMs:posts[1].meta.durationMs,artistAlbumInheritance:true,manualFallback:true,rapidReselection:true,editorReset:true,artworkOverride:true,objectURLs:blobs,viewportWidths:[1280,390],ownerSession:'mocked',publication:'mocked',physicalDevice:false};
}
