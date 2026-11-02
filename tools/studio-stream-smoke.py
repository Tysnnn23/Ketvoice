"""Live, read-only proof that Ketvoice API resolves a playable audio stream today."""
import json, re, urllib.parse, urllib.request, datetime
from pathlib import Path

root = Path(__file__).resolve().parents[1]
key = re.search(r'const val ANON_KEY\s*=\s*"([^"]+)"',
                (root / 'app/src/main/kotlin/com/ketvoice/app/data/KetvoiceApi.kt').read_text()).group(1)
api = 'https://xodklrunqlzyeiqtovgx.supabase.co/functions/v1/ketvoice-api'
headers = {'apikey': key, 'Authorization': 'Bearer ' + key, 'Accept': 'application/json'}
UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36'

def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=45) as r:
        return json.load(r)

def audio_head(url):
    request = urllib.request.Request(url, headers={'User-Agent': UA, 'Range': 'bytes=0-4095'})
    with urllib.request.urlopen(request, timeout=45) as r:
        data = r.read(4096)
        status = r.status
        kind = r.headers.get('content-type', '')
    head = data
    def starts(sig): return head[:len(sig)] == sig
    fmt = None
    if starts(b'fLaC'): fmt = 'flac'
    elif starts(b'OggS'): fmt = 'opus'
    elif len(head) >= 8 and head[4:8] == b'ftyp': fmt = 'aac'
    elif starts(b'ID3'): fmt = 'mp3'
    elif len(head) >= 2 and head[0] == 0xFF and (head[1] & 0xE0) == 0xE0: fmt = 'mp3/aac'
    elif starts(b'RIFF'): fmt = 'wav'
    return {'status': status, 'content_type': kind, 'audio_format': fmt, 'bytes_read': len(head)}

track = {'title': 'Lose Yourself', 'artist': 'Eminem'}
checks = []
# The public search route is the same one the app uses.
search = get_json(f'{api}/search?q=' + urllib.parse.quote('Lose Yourself Eminem'))
checks.append({'route': '/search', 'tracks': len(search.get('tracks', []))})
for source in ('um', 'last'):
    query = urllib.parse.urlencode({**track, 'source': source, 'id': ''})
    try:
        resolved = get_json(f'{api}/stream?{query}')
    except Exception as error:
        checks.append({'route': '/stream', 'source': source, 'error': type(error).__name__})
        continue
    url = resolved.get('url', '')
    entry = {'route': '/stream', 'source': source, 'format': resolved.get('format'),
             'quality': resolved.get('quality'), 'https': url.startswith('https://')}
    if url.startswith('https://'):
        try:
            entry['audio'] = audio_head(url)
        except Exception as error:
            entry['audio'] = {'error': type(error).__name__}
    checks.append(entry)

report = {'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'scope': 'live public Ketvoice API resolution; not device playback',
          'checks': checks}
(root / 'build/ketvoice-stream-live.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
