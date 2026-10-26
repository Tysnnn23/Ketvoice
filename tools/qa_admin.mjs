import {readFile} from 'node:fs/promises';

export default async function run(page) {
  const seen=[];
  const html=await readFile('admin/ketvoice-admin.html','utf8');
  await page.route('https://tysnnn23.github.io/Ketvoice/admin/ketvoice-admin.html*',route=>
    route.fulfill({status:200,contentType:'text/html',body:html}));
  await page.route('https://xodklrunqlzyeiqtovgx.supabase.co/**',async route=>{
    const request=route.request();
    const path=new URL(request.url()).pathname;
    seen.push({path,method:request.method(),redirect:new URL(request.url()).searchParams.get('redirect_to')});
    let response={};
    if(path.endsWith('/admin/session')) response={allowed:true};
    if(path.endsWith('/admin/sources') && request.method()==='GET') response={sources:[]};
    if(path.endsWith('/admin/sources') && request.method()==='POST') response={source:{id:'12345678-1234-1234-1234-123456789abc'}};
    if(path.endsWith('/search')) response={tracks:[{id:'kv:upload:12345678-1234-1234-1234-123456789abc',title:'Demo',artist:'Owner',album:'Test',durationMs:120000}]};
    if(path.endsWith('/admin/song') && request.method()==='GET') response={track:{id:'kv:upload:12345678-1234-1234-1234-123456789abc',title:'Demo',artist:'Owner',album:'Test',durationMs:120000},revision:'mock'};
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(response)});
  });
  await page.locator('#send').click();
  await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('Lien envoyé'));
  await page.goto('https://tysnnn23.github.io/Ketvoice/admin/ketvoice-admin.html#access_token=mock-access&refresh_token=mock-refresh&token_type=bearer&expires_in=3600');
  await page.getByRole('button',{name:/02.*Modifier/}).waitFor({state:'visible'});
  await page.getByRole('button',{name:/02.*Modifier/}).click();
  await page.locator('#search').fill('Demo');
  await page.locator('#searchButton').click();
  await page.getByRole('button',{name:'Modifier mon import'}).click();
  await page.locator('#editForm').waitFor({state:'visible'});
  const editor=await page.locator('#editForm').isVisible();
  await page.getByRole('button',{name:/04.*Sources API/}).click();
  await page.waitForFunction(()=>document.querySelector('#sourceList').textContent.includes('Aucune source'));
  await page.locator('#sourceName').fill('Source de test');
  await page.locator('#sourceUrl').fill('https://example.org/manifest.json');
  await page.getByRole('button',{name:'Ajouter la source'}).click();
  await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('Source ajoutée'));
  const sources=await page.locator('#sources').isVisible();
  if(!editor||!sources||!seen.some(x=>x.path==='/auth/v1/otp'&&x.redirect==='https://tysnnn23.github.io/Ketvoice/admin/ketvoice-admin.html')||!seen.some(x=>x.path.endsWith('/admin/session'))||!seen.some(x=>x.path.endsWith('/admin/sources')&&x.method==='POST'))
    throw Error(JSON.stringify({editor,sources,seen}));
  return {editor,sources,requests:seen};
}
