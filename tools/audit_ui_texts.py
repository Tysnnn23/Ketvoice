"""Extract public UI source messages/templates, never runtime user data or credentials."""
import json
import pathlib
import re

ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=ROOT/'app/src/main/kotlin/com/ketvoice/app'

def strings(text):
    i=0
    while i<len(text):
        if text.startswith('//',i):
            end=text.find('\n',i);i=len(text) if end<0 else end;continue
        if text.startswith('/*',i):
            end=text.find('*/',i+2);i=len(text) if end<0 else end+2;continue
        if text[i]!='"':i+=1;continue
        if text.startswith('"""',i):
            end=text.find('"""',i+3);i=len(text) if end<0 else end+3;continue
        i+=1;out='';arg=0
        while i<len(text) and text[i]!='"':
            if text[i]=='\\' and i+1<len(text):
                out+= {'n':'\n','t':'\t','"':'"',"\\":"\\"}.get(text[i+1], '\\'+text[i+1]);i+=2;continue
            if text.startswith('${',i):
                start=i+2;j=start;depth=1
                while j<len(text) and depth:
                    if text[j]=='"':
                        j+=1
                        while j<len(text) and text[j]!='"':j+=2 if text[j]=='\\' else 1
                    elif text[j]=='{':depth+=1
                    elif text[j]=='}':depth-=1
                    j+=1
                yield from strings(text[start:j-1])
                out+='{'+str(arg)+'}';arg+=1;i=j;continue
            if text[i]=='$':
                match=re.match(r'\$[A-Za-z_]\w*',text[i:])
                if match:out+='{'+str(arg)+'}';arg+=1;i+=len(match[0]);continue
            out+=text[i];i+=1
        yield out
        i+=1

def ui_message(value):
    v=value.strip()
    if not v or len(v)>1500 or '@' in v or '://' in v or '\\' in v:return False
    if v.startswith(('%','[','^','"','Mozilla/','SAPISID=')):return False
    if v.startswith('{') and not re.match(r'\{\d+}',v):return False
    if v in {'MPRE','UC','User-Agent','Content-Type','KetvoiceSettings','Could not restore settings','UTF-8'}:return False
    if '{query}' in v or '{quality}' in v or '{id}' in v:return False
    if '"tracks"' in v or '"name"' in v:return False
    if re.search(r'^[a-z0-9_:./#-]+$',v):return False
    if '/' in v and not re.search(r'\s',v):return False
    if re.search(r'(?:^|\s)(?:import|package)\s',v):return False
    # Generic placeholder-only patterns would swallow user titles or arbitrary status text.
    literal=re.sub(r'\{\d+}', '',v)
    return len(re.findall(r'[^\W\d_]',literal))>=2

def main():
    paths=list((SOURCE/'ui').rglob('*.kt'))+[SOURCE/'MainActivity.kt',SOURCE/'YouTubeLoginActivity.kt']
    paths += [SOURCE/'data'/name for name in ['DesignChoices.kt','Playlists.kt','SpotifyPlaylistImport.kt','OfflineCapsule.kt','CapsulePlan.kt','ImportedLyrics.kt']]
    found=set()
    for path in paths:
        if path.exists():found.update(v for v in strings(path.read_text(encoding='utf-8')) if ui_message(v))
    target=ROOT/'tools/ui-messages.json'
    target.write_text(json.dumps(sorted(found),ensure_ascii=False,indent=1)+'\n',encoding='utf-8')
    print('Audited',len(paths),'UI/message files;',len(found),'public messages/templates.')
    catalog=json.loads((ROOT/'app/src/main/assets/translations.json').read_text(encoding='utf-8'))
    missing=sorted(found-set(catalog['en']))
    print('Missing from bundled English catalog:',len(missing))
    (ROOT/'tools/ui-messages-missing.json').write_text(json.dumps(missing,ensure_ascii=False,indent=1)+'\n',encoding='utf-8')

if __name__=='__main__':main()
