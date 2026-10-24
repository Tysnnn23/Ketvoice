"""Maintainer tool: batch only audited public labels; write all language tables atomically."""
import ast
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
asset=ROOT/'app/src/main/assets/translations.json'
tables=json.loads(asset.read_text(encoding='utf-8'))
messages=json.loads((ROOT/'tools/ui-messages.json').read_text(encoding='utf-8'))
cache_file=ROOT/'tools/.translation-batch-cache.json'
cache=json.loads(cache_file.read_text(encoding='utf-8')) if cache_file.exists() else {}
placeholder=lambda text:set(re.findall(r'\{\d+}',text))
brands={'Ketvoice','KETVOICE','Spotify','SPOTIFY','Sonic Canvas','YouTube','YouTube Music','LRCLIB','LRC','TTML','FLAC','MP3','Google'}

def batches(items):
    group=[];size=0
    for item in items:
        if group and (size+len(item)>1200 or len(group)>=16):yield group;group=[];size=0
        group.append(item);size+=len(item)+14
    if group:yield group

def request(query,language):
    url='https://translate.googleapis.com/translate_a/single?'+urllib.parse.urlencode({'client':'gtx','sl':'fr','tl':'zh-CN' if language=='zh' else language,'dt':'t','q':query})
    req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(req,timeout=40) as response:data=json.load(response)
    return ''.join(part[0] for part in data[0] if part and part[0])

def translated(group,language):
    # Markers are short and alphanumeric so machine translation keeps them verbatim.
    query='\n'.join(f'###KV{i}###{text}' for i,text in enumerate(group))
    text=request(query,language)
    markers=list(re.finditer(r'###\s*KV\s*(\d+)\s*###',text))
    if len(markers)!=len(group) or [int(m.group(1)) for m in markers]!=list(range(len(group))):
        # A merged key can only be recovered one message at a time.
        result=[]
        for message in group:
            value=request(message,language).strip()
            assert value and placeholder(value)==placeholder(message),'Translation changed placeholders'
            result.append(value)
            time.sleep(.4)
        return result
    result=[]
    for i,mark in enumerate(markers):
        value=text[mark.end():markers[i+1].start() if i+1<len(markers) else len(text)].strip()
        assert value and placeholder(value)==placeholder(group[i]),'Translation changed placeholders'
        result.append(value)
    return result

all_keys=set(messages).union(*(set(t) for t in tables.values()))
for language,table in tables.items():
    saved=cache.setdefault(language,{})
    for message in brands:table.setdefault(message,message)
    missing=sorted(all_keys-set(table))
    for group in batches([m for m in missing if m not in saved]):
        values=translated(group,language)
        saved.update(zip(group,values))
        cache_file.write_text(json.dumps(cache,ensure_ascii=False),encoding='utf-8')
        time.sleep(.7)
    for message in missing:table[message]=saved[message]
    print(language,len(table),flush=True)
assert all(set(t)==set(tables['en']) for t in tables.values())
temporary=asset.with_suffix('.json.tmp')
temporary.write_text(json.dumps(tables,ensure_ascii=False,indent=1)+'\n',encoding='utf-8')
temporary.replace(asset)
print('All audited messages bundled in every language.')
