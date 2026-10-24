"""Publish the 3.0.0-Beta2 release (draft -> public) with staged assets."""
import hashlib, json, mimetypes, os, pathlib, urllib.parse, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION, TAG, REPO = '3.0.0-Beta2', 'v3.0.0-Beta2', 'Tysnnn23/Ketvoice'
STAGE = ROOT / 'release-assets' / VERSION
FILES = ['Ketvoice-3.0.0-Beta2.apk', 'Ketvoice-3.0.0-Beta2-Quest.apk',
         'SHA256SUMS.txt', 'RELEASE-NOTES.md', 'validation.json']
token = os.environ.get('GITHUB_TOKEN', '')
assert token, 'GITHUB_TOKEN required'

def api(path, method='GET', data=None, content_type='application/json'):
    url = path if path.startswith('https://') else 'https://api.github.com' + path
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    headers = {'Authorization': 'Bearer ' + token, 'User-Agent': 'Ketvoice-release',
               'Accept': 'application/vnd.github+json'}
    if body is not None:
        headers['Content-Type'] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.load(response)

releases = api(f'/repos/{REPO}/releases?per_page=100')
release = next((r for r in releases if r['tag_name'] == TAG), None)
commit = os.environ.get('RELEASE_COMMIT', '')
if not release:
    release = api(f'/repos/{REPO}/releases', 'POST', {
        'tag_name': TAG, 'target_commitish': commit or 'beta',
        'name': 'Ketvoice ' + VERSION,
        'body': (STAGE / 'RELEASE-NOTES.md').read_text(encoding='utf-8'),
        'draft': True, 'prerelease': True})
upload = release['upload_url'].split('{')[0]
existing = {a['name']: a for a in api(f"/repos/{REPO}/releases/{release['id']}/assets?per_page=100")}
for name in FILES:
    data = (STAGE / name).read_bytes()
    asset = existing.get(name)
    if not asset:
        asset = api(upload + '?name=' + urllib.parse.quote(name), 'POST', data,
                    mimetypes.guess_type(name)[0] or 'application/octet-stream')
    assert asset.get('state') == 'uploaded' and asset.get('size') == len(data), name
    print('Verified asset:', name, len(data), flush=True)
release = api(f"/repos/{REPO}/releases/{release['id']}", 'PATCH',
              {'draft': False, 'make_latest': 'false'})
print('Published:', release['html_url'])

