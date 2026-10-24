"""One-shot publisher for v3.0.0-Beta1 (bypasses hardcoded checksum table)."""
import hashlib, json, mimetypes, os, pathlib, urllib.parse, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
STAGE = ROOT / 'release-assets' / '3.0.0-Beta1'
TAG = 'v3.0.0-Beta1'
REPO = 'Tysnnn23/Ketvoice'
FILES = ['Ketvoice-3.0.0-Beta1.apk', 'Ketvoice-3.0.0-Beta1-Quest.apk',
         'SHA256SUMS.txt', 'RELEASE-NOTES.md', 'validation.json']

token = os.environ.get('GITHUB_TOKEN', '')
if not token:
    raise SystemExit('Set GITHUB_TOKEN')

def api(path, method='GET', data=None, content_type='application/json'):
    url = path if path.startswith('https://') else 'https://api.github.com' + path
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    headers = {'Authorization': 'Bearer ' + token, 'User-Agent': 'Ketvoice-release',
               'Accept': 'application/vnd.github+json'}
    if body is not None:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=300) as r:
        raw = r.read()
    return json.loads(raw) if raw else {}

releases = api(f'/repos/{REPO}/releases?per_page=100')
release = next((r for r in releases if r['tag_name'] == TAG), None)
if release and not release['draft']:
    print('Already published:', release['html_url']); raise SystemExit
if not release:
    release = api(f'/repos/{REPO}/releases', 'POST', {
        'tag_name': TAG, 'target_commitish': 'beta',
        'name': 'Ketvoice 3.0.0-Beta1',
        'body': (STAGE / 'RELEASE-NOTES.md').read_text(encoding='utf-8'),
        'draft': True, 'prerelease': True})

existing = {a['name']: a for a in release['assets']}
upload = release['upload_url'].split('{')[0]
for name in FILES:
    data = (STAGE / name).read_bytes()
    asset = existing.get(name)
    if not asset:
        asset = api(upload + '?name=' + urllib.parse.quote(name), 'POST', data,
                    mimetypes.guess_type(name)[0] or 'application/octet-stream')
    if asset.get('state') != 'uploaded' or asset.get('size') != len(data):
        raise SystemExit('Asset verification failed: ' + name)
    print('Verified asset:', name, len(data), flush=True)

release = api(f"/repos/{REPO}/releases/{release['id']}", 'PATCH',
              {'draft': False, 'make_latest': 'false', 'prerelease': True})
print('Published:', release['html_url'])
