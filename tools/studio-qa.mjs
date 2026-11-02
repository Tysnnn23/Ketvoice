// Run with the browser-automation skill runner against a local HTTP server.
// All Supabase traffic is intercepted: this is UI coverage, NOT an owner session.
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {strict as assert} from 'node:assert';
const providers=JSON.parse(readFileSync(new URL('../app/src/main/assets/provider_registry.json',import.meta.url),'utf8'));
export default async function(page){
  let rules=[],revision='00000000-0000-0000-0000-000000000000',writes=0,refreshes=0;
  await page.route('https://xodklrunqlzyeiqtovgx.supabase.co/**',async route=>{
    const request=route.request(),url=new URL(request.url()),method=request.method();let body={};
    if(method==='OPTIONS')return route.fulfill({status:204,headers:{'access-control-allow-origin':'*','access-control-allow-headers':'*','access-control-allow-methods':'*'}});
    if(url.pathname.endsWith('/admin/session'))body={allowed:true};
    else if(url.pathname.endsWith('/auth/v1/logout'))body={};
    else if(url.pathname.endsWith('/auth/v1/token')){refreshes++;body={access_token:'refreshed-ui-fixture',refresh_token:'rotated-fixture',expires_in:3600};}
    else if(url.pathname.endsWith('/admin/providers')){
      if(method==='PUT'){const payload=request.postDataJSON();assert.equal(payload.revision,revision);rules=payload.rules;revision='00000000-0000-0000-0000-000000000001';writes++;}
      body={providers,rules,revision,updated_at:'2026-11-02T16:00:00Z'};
    }
    else if(url.pathname.endsWith('/admin/albums'))body={albums:[]};
    else if(url.pathname.endsWith('/listeners'))body={activeListeners:17};
    else if(url.pathname.endsWith('/admin/catalogue')){
      const kind=url.searchParams.get('kind');
      body={results:[{id:kind==='track'?'yt:123456789ab':'UC123456789012',title:'QA catalogue result',artist:'QA artist',source:'YouTube Music',kind,editable:false}],warnings:[]};
    }
    else throw Error('Unmocked request: '+url.pathname);
    return route.fulfill({status:200,contentType:'application/json',headers:{'access-control-allow-origin':'*'},body:JSON.stringify(body)});
  });
  await page.evaluate(()=>localStorage.setItem('kv-studio-session',JSON.stringify({access_token:'ui-fixture-not-a-token',refresh_token:'fixture',expiresAt:0})));
  await page.reload();
  await page.locator('#activeCount').filter({hasText:'17'}).waitFor();
  assert.equal(refreshes,1);assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('kv-studio-session')).refresh_token),'rotated-fixture');
  await page.locator('[data-view="providers"]').click();
  await page.locator('.provider').first().waitFor();
  assert.equal(await page.locator('.provider').count(),providers.length);
  assert.equal(await page.locator('input[id^="provider-"]').count(),10);
  await page.locator('#provider-deezer').fill('https://example.com/json/');
  await page.locator('#providerQuery').fill('Canvas');
  assert.equal(await page.locator('#provider-deezer').count(),0);
  await page.locator('#providerQuery').fill('');
  assert.equal(await page.locator('#provider-deezer').inputValue(),'https://example.com/json/');
  await page.locator('#providerSave').click();
  await page.getByText('Configuration enregistrée.',{exact:false}).waitFor();
  assert.equal(writes,1);assert.equal(rules.find(r=>r.id==='deezer').base,'https://example.com/json/');
  await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:'build/studio-providers-qa.png'});
  await page.locator('[data-view="catalogue"]').click();
  await page.locator('#catalogueQuery').fill('QA query');
  await page.locator('#catalogueForm button').click();
  await page.getByText('QA catalogue result',{exact:true}).waitFor();
  await page.getByRole('button',{name:'Détails et actions'}).click();
  await page.getByRole('button',{name:'Importer des paroles'}).waitFor();
  assert.equal(await page.getByRole('button',{name:'Modifier mon import'}).count(),0);
  await page.getByRole('button',{name:'Importer des paroles'}).click();
  assert.equal(await page.locator('#lyricId').inputValue(),'yt:123456789ab');
  await page.locator('[data-view="catalogue"]').click();
  await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:'build/studio-catalogue-qa.png'});
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);assert.equal(overflow,false);
  await page.setViewportSize({width:390,height:844});
  await page.locator('[data-view="providers"]').click();
  await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:'build/studio-mobile-qa.png'});
  const mobile=await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth+1,width:innerWidth,scrollWidth:document.documentElement.scrollWidth,offenders:[...document.querySelectorAll('body *')].filter(n=>n.getBoundingClientRect().right>innerWidth+1).map(n=>({tag:n.tagName,id:n.id,class:n.className,right:n.getBoundingClientRect().right})).slice(0,15)}));
  assert.equal(mobile.overflow,false,JSON.stringify(mobile));
  await page.locator('#logout').click();
  await page.locator('#login').waitFor({state:'visible'});
  assert.equal(await page.evaluate(()=>localStorage.getItem('kv-studio-session')),null);
  const report={captured_at:new Date().toISOString(),mode:'mocked UI only',providers:providers.length,editableBases:10,writes,refreshes,catalogueLyricsId:'yt:123456789ab',desktopOverflow:overflow,mobileOverflow:false,logoutCleared:true,
    source_sha256:createHash('sha256').update(readFileSync(new URL('../admin/ketvoice-admin.html',import.meta.url))).digest('hex')};
  writeFileSync(new URL('../build/studio-ui-qa.json',import.meta.url),JSON.stringify(report,null,2));return report;
}
