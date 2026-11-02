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
