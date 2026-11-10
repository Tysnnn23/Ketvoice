"""Extract public UI source messages/templates, never runtime user data or credentials.

Run with --check to fail when a user-facing message has no bundled English
translation. Strings that the scanner picks up but that are not user text
(cache keys, HTTP headers, animation tags, file patterns) must be listed in
tools/ui-text-ignored.json so the gap stays visible and reviewed.
"""
import json
import pathlib
import re
import sys

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

    literal=re.sub(r'\{\d+}', '',v)
    return len(re.findall(r'[^\W\d_]',literal))>=2

def ignored():
    path=ROOT/'tools/ui-text-ignored.json'
    if not path.exists():return {}
    data=json.loads(path.read_text(encoding='utf-8'))
    assert isinstance(data,dict),'tools/ui-text-ignored.json must map message -> reason'
    return data

def main():
    check='--check' in sys.argv[1:]
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
    skip=ignored()
    unaccounted=[m for m in missing if m not in skip]
    stale=sorted(set(skip)&set(catalog['en']))
    print('Missing from bundled English catalog:',len(missing),
          f'({len(unaccounted)} unaccounted, {len(missing)-len(unaccounted)} non-UI in tools/ui-text-ignored.json)')
    (ROOT/'tools/ui-messages-missing.json').write_text(json.dumps(missing,ensure_ascii=False,indent=1)+'\n',encoding='utf-8')
    if stale:
        print('Stale ui-text-ignored.json entries (now translated, remove them):',len(stale))
        for message in stale:print('  ',repr(message))
    if unaccounted:
        print('Untranslated user-facing messages:')
        for message in unaccounted[:40]:print('  ',repr(message))
    if check and (unaccounted or stale):
        return 1
    return 0

if __name__=='__main__':sys.exit(main())
