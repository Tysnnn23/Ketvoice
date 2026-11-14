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
    if(url.includes("/rest/v1/ketvoice_media_overrides"))return Response.json([{kind:"artist",key:"daft punk",label:"Daft Punk",cover_path:null,canvas_path:null,hero_path:"x/hero.jpg",updated_at:"2026-11-14"}]);
    throw new Error("Unexpected external request "+url);
  };
  try{
    const list=await handler(new Request("https://example.com/ketvoice-content/overrides"));
    assert(list.status===200);assert(list.headers.get("cache-control")?.includes("public"));
    const body=await list.json();assert(body.overrides[0].heroURL.endsWith("/ketvoice-public-media/x/hero.jpg"));assert(body.overrides[0].coverURL===null);
    const write=await handler(new Request("https://example.com/ketvoice-content/admin/override",{method:"POST",body:new FormData()}));
    assert(write.status===401);
  }finally{globalThis.fetch=original;if(previous===undefined)Deno.env.delete("SUPABASE_URL");else Deno.env.set("SUPABASE_URL",previous);}
});
Deno.test("catalogue overrides validate keys and fields, merge, and restore by deleting",async()=>{
  const original=globalThis.fetch;
  const previous=Deno.env.get("SUPABASE_URL");
  Deno.env.set("SUPABASE_URL","https://project.example.com");
  const user={id:"owner",email:"konnektsupport@gmail.com",email_confirmed_at:"2026-01-01",is_anonymous:false};
  let row=null as Record<string,unknown>|null;let stored=0,removedFiles:string[]=[];
  globalThis.fetch=async(input,init)=>{
    const url=String(input);
    if(url.endsWith("/auth/v1/user"))return Response.json(user);
    if(url.includes("/storage/v1/object/ketvoice-public-media/")){stored++;return new Response("{}");}
    if(url.endsWith("/storage/v1/object/ketvoice-public-media")&&init?.method==="DELETE"){removedFiles.push(...JSON.parse(String(init.body)).prefixes);return new Response("{}");}
    if(url.includes("/rest/v1/ketvoice_media_overrides")){
      if(init?.method==="POST"){assert(url.includes("on_conflict=kind,key"));row=JSON.parse(String(init.body));return Response.json([row]);}
      if(init?.method==="DELETE"){row=null;return new Response("");}
      return Response.json(row?[row]:[]);
    }
    throw new Error("Unexpected external request "+url);
  };
  const cur=()=>row;
  const jpeg=new Uint8Array([255,216,255,224,0,16,74,70,73,70,0,1]);
  const mp4=new Uint8Array([0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0]);
  const request=(meta:unknown,files:Record<string,Uint8Array>={})=>{
    const form=new FormData();form.set("metadata",JSON.stringify(meta));
    for(const [name,data] of Object.entries(files))form.set(name,new File([data],name));
    return new Request("https://example.com/ketvoice-content/admin/override",{method:"POST",headers:{"x-ketvoice-admin":"fixture-only"},body:form});
  };
  const album={kind:"album",key:"discovery|daft punk",label:"Discovery",rightsConfirmed:true};
  try{
    assert((await handler(request({...album,rightsConfirmed:false},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,key:"Discovery|Daft Punk"},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,key:"discovery"},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,kind:"playlist"},{cover:jpeg}))).status===400);
    assert((await handler(request(album,{hero:jpeg}))).status===400);
    assert((await handler(request(album))).status===400);
    assert(stored===0);
    const first=await handler(request(album,{cover:jpeg}));
    assert(first.status===200);assert(stored===1);assert(String(cur()?.cover_path).endsWith("/cover.jpg"));
    const cover=cur()?.cover_path;
    const second=await handler(request(album,{canvas:mp4}));
    assert(second.status===200);assert(cur()?.cover_path===cover);assert(String(cur()?.canvas_path).endsWith("/canvas.mp4"));
    assert((await handler(request({...album,remove:["cover"]},{cover:jpeg}))).status===400);
    assert((await handler(request({...album,remove:["hero"]}))).status===400);
    const third=await handler(request({...album,remove:["cover"]}));
    assert(third.status===200);assert(cur()?.cover_path===null);assert(removedFiles.includes(String(cover)));
    const last=await handler(request({...album,remove:["canvas"]}));
    assert(last.status===200);assert(cur()===null);assert((await last.json()).restored===true);
    const rect={cx:0.12345678,cy:-0.3,w:0.6,h:0.45,rotation:97.126,flip:true};
    const framed=await handler(request({...album,frame:rect}));
    assert(framed.status===200);
    const kept=cur()?.frame as Record<string,unknown>;
    assert(kept.cx===0.1235);assert(kept.rotation===97.13);assert(kept.flip===true);assert(cur()?.cover_path==null);
    const shown=(await framed.json()).override.frame;
    assert(shown.w===0.6);assert(shown.scale===1);assert(shown.x===0);
    assert((await handler(request({...album,frame:{...rect,w:9}}))).status===400);
    assert((await handler(request({...album,frame:{...rect,rotation:400}}))).status===400);
    assert((await handler(request({...album,frame:{...rect,flip:"yes"}}))).status===400);
    assert((await handler(request({...album,frame:{...rect,extra:1}}))).status===400);
    assert((await handler(request({...album,frame:{scale:1.4,x:0,y:0}}))).status===400);
    assert((await handler(request({...album,frame:rect,remove:["frame"]}))).status===400);
    const unframed=await handler(request({...album,remove:["frame"]}));
    assert(unframed.status===200);assert(cur()===null);
    const artist=await handler(request({kind:"artist",key:"daft punk",rightsConfirmed:true},{hero:jpeg}));
    assert((await handler(request({kind:"artist",key:"daft punk",rightsConfirmed:true,frame:{cx:0,cy:0,w:1,h:1}}))).status===400);
    assert(artist.status===200);assert(String(cur()?.hero_path).endsWith("/hero.jpg"));
    assert((await handler(request({kind:"artist",key:"daft punk",rightsConfirmed:true},{canvas:mp4}))).status===400);
    assert((await handler(request({kind:"track",key:"one more time|daft punk",rightsConfirmed:true},{canvas:new Uint8Array(32)}))).status===400);
  } finally {
    globalThis.fetch=original;
    if(previous===undefined)Deno.env.delete("SUPABASE_URL");else Deno.env.set("SUPABASE_URL",previous);
  }
});
