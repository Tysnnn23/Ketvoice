"""Live public and unauthenticated boundaries, never owner settings mutations."""
import json,re,urllib.request,urllib.error,datetime
from pathlib import Path
root=Path(__file__).resolve().parents[1]
key=re.search(r'const val ANON_KEY\s*=\s*"([^"]+)"',(root/'app/src/main/kotlin/com/ketvoice/app/data/KetvoiceApi.kt').read_text()).group(1)
base='https://xodklrunqlzyeiqtovgx.supabase.co/functions/v1/ketvoice-content'
results=[]
for path,expected in [('/health',200),('/providers',200),('/admin/providers',401),('/admin/catalogue?q=Eminem&kind=artist',401),('/admin/song?id=yt:123456789ab',401)]:
    request=urllib.request.Request(base+path,headers={'apikey':key,'Authorization':'Bearer '+key})
    try:
        response=urllib.request.urlopen(request,timeout=45);status=response.status;body=json.load(response)
    except urllib.error.HTTPError as error:
        status=error.code;body=json.load(error)
    assert status==expected,(path,status)
    if path=='/providers':
        assert set(body)=={'revision','rules','updated_at'} and isinstance(body['rules'],list)
    results.append({'path':path,'status':status,'expected':expected})
report={'captured_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'live public read and denied anonymous admin access; no owner settings or uploads','checks':results}
(root/'build/studio-backend-live.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
