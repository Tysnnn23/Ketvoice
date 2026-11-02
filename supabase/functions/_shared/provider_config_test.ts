import {validateRules,providers} from "./provider_config.ts";
function assert(value:unknown,message="Assertion failed"){if(!value)throw new Error(message);}
Deno.test("provider rules reject protected IDs, duplicates, credential URLs and private networks",async()=>{
  for(const rules of [
    [{id:"supabase-auth",enabled:false,base:""}],
    [{id:"unknown",enabled:true,base:""}],
    [{id:"stream-um",enabled:true,base:"https://example.com/"}],
    [{id:"stream-um",enabled:true,base:""},{id:"stream-um",enabled:false,base:""}],
    [{id:"deezer",enabled:true,base:"http://example.com/"}],
    [{id:"deezer",enabled:true,base:"https://user:pass@example.com/"}],
    [{id:"deezer",enabled:true,base:"https://example.com/?token=private"}],
    [{id:"deezer",enabled:true,base:"https://127.0.0.1/"}],
    [{id:"deezer",enabled:true,base:"https://localhost/"}],
    [{id:"deezer",enabled:true,base:"https://example.com/api"}],
    [{id:"stream-um",enabled:"true",base:""}],
  ]) {
    let failed=false;try{await validateRules(rules);}catch{failed=true;}assert(failed,JSON.stringify(rules));
  }
});
Deno.test("provider inventory and stream switches have stable IDs",async()=>{
  assert(new Set(providers.map(p=>p.id)).size===providers.length);
  assert(providers.filter(p=>p.editable).length===10);
  const rules=await validateRules([{id:"stream-um",enabled:false,base:""}]);
  assert(rules[0].enabled===false);assert((await validateRules([])).length===0);
  assert((await validateRules([{id:"liriqo",enabled:false,base:providers.find(p=>p.id==="liriqo")!.base}]))[0].enabled===false);
});
