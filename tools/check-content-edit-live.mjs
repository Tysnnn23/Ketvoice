
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../app/src/main/kotlin/com/ketvoice/app/data/KetvoiceApi.kt',import.meta.url),'utf8');
const key=source.match(/const val ANON_KEY\s*=\s*"([^"]+)"/)?.[1];
if(!key)throw new Error('Public client key not found');
const base='https://xodklrunqlzyeiqtovgx.supabase.co/functions/v1/ketvoice-content';
for(const [path,method,status] of [['/health','GET',200],['/admin/albums','GET',401],['/admin/song?id=11111111-1111-4111-8111-111111111111','GET',401],['/admin/song/update','POST',401]]){
 const response=await fetch(base+path,{method,headers:{apikey:key,authorization:`Bearer ${key}`}});
 if(response.status!==status)throw new Error(`${path}: unexpected status ${response.status}`);
 console.log(`${method} ${path.split('?')[0]}: ${status}`);
}
