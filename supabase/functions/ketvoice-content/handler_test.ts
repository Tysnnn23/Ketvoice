import {handler} from "./handler.ts";
function assert(value:unknown){if(!value)throw new Error("Assertion failed");}
Deno.test("all administrative catalogue and provider endpoints require owner credentials",async()=>{
  for(const path of ["/admin/providers","/admin/catalogue?q=Eminem&kind=track","/admin/catalogue/detail?id=UC123456789012&kind=artist","/admin/song?id=yt:123456789ab"]){
    const response=await handler(new Request("https://example.com/ketvoice-content"+path));assert(response.status===401);
  }
});
Deno.test("public clients cannot write provider configuration and CORS permits authenticated PUT",async()=>{
  const response=await handler(new Request("https://example.com/ketvoice-content/providers",{method:"PUT",body:"{}"}));assert(response.status===404);
  const options=await handler(new Request("https://example.com/ketvoice-content/admin/providers",{method:"OPTIONS"}));assert(options.headers.get("access-control-allow-methods")?.includes("PUT"));
});
Deno.test("provider mutations validate verified owner and enforce revision conflicts",async()=>{
  const original=globalThis.fetch;
  const previous=Deno.env.get("SUPABASE_URL");
  Deno.env.set("SUPABASE_URL","https://project.example.com");
  let user={id:"owner",email:"other@example.com",email_confirmed_at:"2026-01-01",is_anonymous:false};
  let writes=0,conflict=false;
  globalThis.fetch=async(input,init)=>{
    const url=String(input);
    if(url.endsWith("/auth/v1/user"))return Response.json(user);
    if(url.includes("/rest/v1/ketvoice_provider_config")&&init?.method==="PATCH"){
      writes++;return Response.json(conflict?[]:[{...JSON.parse(String(init.body)),revision:"new"}]);
    }
    throw new Error("Unexpected external request");
  };
  const request=(body:unknown)=>new Request("https://example.com/ketvoice-content/admin/providers",{method:"PUT",headers:{"x-ketvoice-admin":"fixture-only"},body:JSON.stringify(body)});
  const revision="00000000-0000-0000-0000-000000000000";
  try{
    assert((await handler(request({revision,rules:[]}))).status===403);assert(writes===0);
    user={...user,email:"konnektsupport@gmail.com",email_confirmed_at:""};
    assert((await handler(request({revision,rules:[]}))).status===403);
    user={...user,email_confirmed_at:"2026-01-01"};
    assert((await handler(request({revision,rules:[{id:"supabase-auth",enabled:false,base:""}]}))).status===400);assert(writes===0);
    assert((await handler(request({revision,rules:[{id:"stream-um",enabled:false,base:""}]}))).status===200);assert(writes===1);
    conflict=true;assert((await handler(request({revision,rules:[]}))).status===409);
  }finally{globalThis.fetch=original;if(previous===undefined)Deno.env.delete("SUPABASE_URL");else Deno.env.set("SUPABASE_URL",previous);}
});

Deno.test("studio list and selection media require owner credentials",async()=>{
  for(const [path,method] of [["/admin/songs","GET"],["/admin/media","POST"]]){
    const response=await handler(new Request("https://example.com/ketvoice-content"+path,{method}));assert(response.status===401);
  }
});
Deno.test("selection media only touches titles the owner published and stores the file once",async()=>{
  const original=globalThis.fetch;
  const previous=Deno.env.get("SUPABASE_URL");
  Deno.env.set("SUPABASE_URL","https://project.example.com");
  const user={id:"owner",email:"konnektsupport@gmail.com",email_confirmed_at:"2026-01-01",is_anonymous:false};
  const a="11111111-1111-4111-8111-111111111111",b="22222222-2222-4222-8222-222222222222";
  let ownedIds=[a,b],stored=0,patched="",deleted=0;
  globalThis.fetch=async(input,init)=>{
    const url=String(input);
    if(url.endsWith("/auth/v1/user"))return Response.json(user);
    if(url.includes("/storage/v1/object/ketvoice-public-media/")){stored++;return new Response("{}");}
    if(url.endsWith("/storage/v1/object/ketvoice-public-media")&&init?.method==="DELETE"){deleted++;return new Response("{}");}
    // The database answers like PostgREST: only owned rows among the requested ids.
    const requested=(url.match(/id=in\.\(([^)]*)\)/)?.[1] ?? "").split(",");
    const visible=ownedIds.filter(id=>requested.includes(id));
    if(url.includes("/rest/v1/ketvoice_tracks")&&url.includes("select=id"))return Response.json(visible.map(id=>({id})));
    if(url.includes("/rest/v1/ketvoice_tracks")&&init?.method==="PATCH"){patched=String(init.body);
      return Response.json(visible.map(id=>({id,title:"T",artist:"A",album:"B",audio_path:`${id}/a.mp3`,canvas_path:JSON.parse(String(init.body)).canvas_path ?? null})));}
    throw new Error("Unexpected external request "+url);
  };
  const mp4=new Uint8Array([0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0]);
  const request=(meta:unknown,canvas=true)=>{
    const form=new FormData();form.set("metadata",JSON.stringify(meta));
    if(canvas)form.set("canvas",new File([mp4],"canvas.mp4"));
    return new Request("https://example.com/ketvoice-content/admin/media",{method:"POST",headers:{"x-ketvoice-admin":"fixture-only"},body:form});
  };
  try{
    assert((await handler(request({trackIds:[a,b]}))).status===400);
    assert((await handler(request({trackIds:[],rightsConfirmed:true}))).status===400);
    assert((await handler(request({trackIds:["yt:abc"],rightsConfirmed:true}))).status===400);
    ownedIds=[a];
    assert((await handler(request({trackIds:[a,b],rightsConfirmed:true}))).status===404);assert(stored===0);
    ownedIds=[a,b];
    const ok=await handler(request({trackIds:[a,b,a],rightsConfirmed:true}));
    assert(ok.status===200);assert(stored===1);assert(JSON.parse(patched).canvas_path.endsWith("/canvas.mp4"));
    assert((await ok.json()).updated===2);
    assert((await handler(request({trackIds:[a],rightsConfirmed:true,removeCanvas:true}))).status===400);
    const removed=await handler(request({trackIds:[a],rightsConfirmed:true,removeCanvas:true},false));
    assert(removed.status===200);assert(JSON.parse(patched).canvas_path===null);
    const bad=new FormData();bad.set("metadata",JSON.stringify({trackIds:[a],rightsConfirmed:true}));bad.set("canvas",new File([new Uint8Array(32)],"x.mp4"));
    assert((await handler(new Request("https://example.com/ketvoice-content/admin/media",{method:"POST",headers:{"x-ketvoice-admin":"fixture-only"},body:bad}))).status===400);
  } finally {
    globalThis.fetch=original;
    if(previous===undefined)Deno.env.delete("SUPABASE_URL");else Deno.env.set("SUPABASE_URL",previous);
  }
});

Deno.test("catalogue overrides: public list is readable, writes need the owner",async()=>{
  const original=globalThis.fetch;
  const previous=Deno.env.get("SUPABASE_URL");
  Deno.env.set("SUPABASE_URL","https://project.example.com");
  globalThis.fetch=async(input)=>{
    const url=String(input);
    if(url.includes("/rest/v1/ketvoice_media_overrides"))return Response.json([{kind:"artist",key:"daft punk",label:"Daft Punk",cover_path:null,canvas_path:null,hero_path:"x/hero.jpg",canvas_off:true,updated_at:"2026-11-14"}]);
    throw new Error("Unexpected external request "+url);
  };
  try{
    const list=await handler(new Request("https://example.com/ketvoice-content/overrides"));
    assert(list.status===200);assert(list.headers.get("cache-control")?.includes("public"));
    const body=await list.json();assert(body.overrides[0].heroURL.endsWith("/ketvoice-public-media/x/hero.jpg"));assert(body.overrides[0].coverURL===null);assert(body.overrides[0].canvasOff===true);
    const write=await handler(new Request("https://example.com/ketvoice-content/admin/override",{method:"POST",body:new FormData()}));
    assert(write.status===401);
  }finally{globalThis.fetch=original;if(previous===undefined)Deno.env.delete("SUPABASE_URL");else Deno.env.set("SUPABASE_URL",previous);}
});
/** A tiny stand-in for the project: the overrides table, the storage bucket and the owner's session. */
function fakeProject() {
  const rows=new Map<string,Record<string,unknown>>();
  const files=new Set<string>(), deleted: string[]=[];
  const user={id:"owner",email:"konnektsupport@gmail.com",email_confirmed_at:"2026-01-01",is_anonymous:false};
  const handle=async(input: string,init?: RequestInit): Promise<Response>=>{
    const url=String(input);
    if(url.endsWith("/auth/v1/user")) return Response.json(user);
    if(url.includes("/storage/v1/object/ketvoice-public-media/")){files.add(url.split("/ketvoice-public-media/")[1]);return new Response("{}");}
    if(url.endsWith("/storage/v1/object/ketvoice-public-media")&&init?.method==="DELETE"){
      for(const path of JSON.parse(String(init.body)).prefixes){deleted.push(path);files.delete(path);}return new Response("{}");}
    if(url.includes("/rest/v1/ketvoice_media_overrides")){
      const query=decodeURIComponent(url.split("?")[1] ?? "");
      if(init?.method==="POST"){
        assert(query.includes("on_conflict=kind,key"));
        const body=JSON.parse(String(init.body)) as Record<string,unknown>[];assert(Array.isArray(body));
        for(const row of body)rows.set(`${row.kind}:${row.key}`,row);
        return Response.json(body);
      }
      const inKeys=(query.match(/key=in\.\((.*?)\)(?:&|$)/)?.[1] ?? "").split(",").map(k=>k.replace(/"/g,"")).filter(Boolean);
      const kind=query.match(/kind=eq\.(\w+)/)?.[1];
      if(init?.method==="DELETE"){for(const key of inKeys)rows.delete(`${kind}:${key}`);return new Response("");}
      const paths=query.match(/or=\((.*)\)/)?.[1];
      if(paths){
        const wanted=paths.split(",").map(p=>p.split(".eq.")[1]);
        return Response.json([...rows.values()].filter(r=>wanted.some(w=>r.cover_path===w||r.canvas_path===w||r.hero_path===w)).map(r=>({key:r.key})));
      }
      return Response.json([...rows.values()].filter(r=>r.kind===kind && inKeys.includes(String(r.key))));
    }
    throw new Error("Unexpected external request "+url);
  };
  return {rows,files,deleted,handle};
}
const jpeg=new Uint8Array([255,216,255,224,0,16,74,70,73,70,0,1]);
const mp4=new Uint8Array([0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0]);
function overrideRequest(meta:unknown,files:Record<string,Uint8Array>={}){
  const form=new FormData();form.set("metadata",JSON.stringify(meta));
  for(const [name,data] of Object.entries(files))form.set(name,new File([data],name));
  return new Request("https://example.com/ketvoice-content/admin/override",{method:"POST",headers:{"x-ketvoice-admin":"fixture-only"},body:form});
}
async function withProject(run:(project:ReturnType<typeof fakeProject>)=>Promise<void>){
  const original=globalThis.fetch,previous=Deno.env.get("SUPABASE_URL");
  Deno.env.set("SUPABASE_URL","https://project.example.com");
  const project=fakeProject();globalThis.fetch=(input,init)=>project.handle(String(input),init);
  try{await run(project);}finally{globalThis.fetch=original;if(previous===undefined)Deno.env.delete("SUPABASE_URL");else Deno.env.set("SUPABASE_URL",previous);}
}
Deno.test("catalogue overrides validate keys and fields, merge, and restore by deleting",async()=>{
  await withProject(async({rows,deleted})=>{
    const album={kind:"album",key:"discovery|daft punk",label:"Discovery",rightsConfirmed:true};
    const request=overrideRequest;
    assert((await handler(request({...album,rightsConfirmed:false},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,key:"Discovery|Daft Punk"},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,key:"discovery"},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,kind:"playlist"},{cover:jpeg}))).status===400);
    assert((await handler(request(album,{hero:jpeg}))).status===400);
    assert((await handler(request(album))).status===400);
    assert(rows.size===0);
    const first=await handler(request(album,{cover:jpeg}));
    assert(first.status===200);const row=()=>rows.get("album:discovery|daft punk");
    assert(String(row()?.cover_path).endsWith("/cover.jpg"));
    const cover=row()?.cover_path;
    const second=await handler(request(album,{canvas:mp4}));
    assert(second.status===200);assert(row()?.cover_path===cover);assert(String(row()?.canvas_path).endsWith("/canvas.mp4"));
    assert((await handler(request({...album,remove:["cover"]},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,remove:["hero"]}))).status===400);
    const third=await handler(request({...album,remove:["cover"]}));
    assert(third.status===200);assert(row()?.cover_path===null);assert(deleted.includes(String(cover)));
    const rect={cx:0.12345678,cy:-0.3,w:0.6,h:0.45,rotation:97.126,flip:true};
    const framed=await handler(request({...album,frame:rect}));
    assert(framed.status===200);
    const kept=row()?.frame as Record<string,unknown>;
    assert(kept.cx===0.1235);assert(kept.rotation===97.13);assert(kept.flip===true);assert(row()?.cover_path===null);
    const shown=(await framed.json()).override.frame;
    assert(shown.w===0.6);assert(shown.scale===1);assert(shown.x===0);
    assert((await handler(request({...album,frame:{...rect,w:9}}))).status===400);
    assert((await handler(request({...album,frame:{...rect,rotation:400}}))).status===400);
    assert((await handler(request({...album,frame:{...rect,flip:"yes"}}))).status===400);
    assert((await handler(request({...album,frame:{...rect,extra:1}}))).status===400);
    assert((await handler(request({...album,frame:{scale:1.4,x:0,y:0}}))).status===400);
    assert((await handler(request({...album,frame:rect,remove:["frame"]}))).status===400);
    const unframed=await handler(request({...album,remove:["frame"]}));
    assert(unframed.status===200);
    // The canvas is still there: only the framing went.
    assert(row()?.frame===null);assert(String(row()?.canvas_path).endsWith("/canvas.mp4"));
    const last=await handler(request({...album,remove:["canvas"]}));
    assert(last.status===200);assert(row()===undefined);assert((await last.json()).restored===true);
    const artist=await handler(request({kind:"artist",key:"daft punk",rightsConfirmed:true},{hero:jpeg}));
    assert((await handler(request({kind:"artist",key:"daft punk",rightsConfirmed:true,frame:{cx:0,cy:0,w:1,h:1}}))).status===400);
    assert(artist.status===200);assert(String(rows.get("artist:daft punk")?.hero_path).endsWith("/hero.jpg"));
    assert((await handler(request({kind:"artist",key:"daft punk",rightsConfirmed:true},{canvas:mp4}))).status===400);
    assert((await handler(request({kind:"track",key:"one more time|daft punk",rightsConfirmed:true},{canvas:new Uint8Array(32)}))).status===400);
  });
});
Deno.test("a selection of titles gets one canvas, switched off, or restored, in one request",async()=>{
  await withProject(async({rows,files,deleted})=>{
    const titles=["one more time|daft punk","aerodynamic|daft punk","digital love|daft punk"];
    const items=titles.map(key=>({key,label:key.split("|")[0]}));
    const base={kind:"track",rightsConfirmed:true};
    const row=(key:string)=>rows.get(`track:${key}`);
    // One file, stored once, named by every selected title.
    const sent=await handler(overrideRequest({...base,items},{canvas:mp4}));
    assert(sent.status===200);
    assert((await sent.json()).overrides.length===3);
    assert(files.size===1);
    const shared=String(row(titles[0])?.canvas_path);
    assert(titles.every(t=>row(t)?.canvas_path===shared));assert(row(titles[1])?.label==="aerodynamic");
    // Switching the canvas off for two of them: they keep a row that says "off" and lose the path; the file stays for the third.
    const off=await handler(overrideRequest({...base,items:items.slice(0,2),canvasOff:true}));
    assert(off.status===200);
    assert(row(titles[0])?.canvas_off===true&&row(titles[0])?.canvas_path===null);assert(row(titles[1])?.canvas_off===true);
    assert(row(titles[2])?.canvas_off===false&&row(titles[2])?.canvas_path===shared);
    assert(!deleted.includes(shared));assert(files.has(shared));
    const view=(await off.json()).overrides[0];assert(view.canvasOff===true&&view.canvasURL===null);
    // A new canvas for one of them switches it back on; the old file goes once nobody uses it.
    const changed=await handler(overrideRequest({...base,items:[items[2]]},{canvas:mp4}));
    assert(changed.status===200);assert(row(titles[2])?.canvas_path!==shared);assert(deleted.includes(shared));
    // Back on without a file: the catalogue's own canvas shows again and the row disappears.
    const on=await handler(overrideRequest({...base,items:items.slice(0,2),canvasOff:false}));
    assert(on.status===200);assert(row(titles[0])===undefined&&row(titles[1])===undefined);assert(row(titles[2])!==undefined);
    assert((await on.json()).removed.length===2);
    // Refusals.
    assert((await handler(overrideRequest({...base,items:[]},{canvas:mp4}))).status===400);
    assert((await handler(overrideRequest({...base,items:Array.from({length:81},(_,i)=>({key:`t${i}|a`}))},{canvas:mp4}))).status===400);
    assert((await handler(overrideRequest({...base,items,canvasOff:true},{canvas:mp4}))).status===400);
    assert((await handler(overrideRequest({...base,items,canvasOff:true,remove:["canvas"]}))).status===400);
    assert((await handler(overrideRequest({...base,items:[{key:"Bad Key|x"}],canvasOff:true}))).status===400);
    assert((await handler(overrideRequest({...base,key:titles[0],items,canvasOff:true}))).status===400);
    assert((await handler(overrideRequest({kind:"artist",rightsConfirmed:true,items:[{key:"daft punk"}],canvasOff:true}))).status===400);
    assert((await handler(overrideRequest({kind:"album",rightsConfirmed:true,items:[{key:"a|b"},{key:"c|d"}],frame:{cx:0,cy:0,w:1,h:1}}))).status===400);
    // An album's canvas can be switched off for the whole album, and a title can still bring its own.
    const album=await handler(overrideRequest({kind:"album",rightsConfirmed:true,key:"discovery|daft punk",canvasOff:true}));
    assert(album.status===200);assert(rows.get("album:discovery|daft punk")?.canvas_off===true);
  });
});
