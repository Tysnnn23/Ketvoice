"""Publish a checked release using existing GitHub credentials, kept in memory.

Run only after committing and pushing the validated source. Starts as a draft;
uploads are checked before --publish makes the release public.
"""
import argparse
import hashlib
import json
import mimetypes
import os
import pathlib
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--publish', action='store_true')
parser.add_argument('--version', choices=['2.6.0', '2.6.1', '2.7.0', '2.7.1', '2.8.0'], default='2.6.0')
args = parser.parse_args()
VERSION = args.version
TAG = 'v' + VERSION
REPO = 'Tysnnn23/Ketvoice'
STAGE = ROOT / 'release-assets' / VERSION
FILES = [
    f'Ketvoice-{VERSION}.apk', 'SHA256SUMS.txt', 'RELEASE-NOTES.md', 'validation.json',
]
if VERSION == '2.6.0':
    FILES += ['tablet-navigation.png', 'tablet-search.png', 'tablet-player.png',
        'tablet-settings.png', 'phone-search-songs.png']
if VERSION == '2.7.0':
    FILES += ['Ketvoice-2.7.0-Quest.apk', 'quest-mobile-style.png']
if VERSION == '2.7.1':
    FILES[0] = 'Ketvoice-2.7.1-Quest.apk'
    FILES += ['quest-landscape-lyrics.png', 'quest-landscape-queue.png']
if VERSION == '2.8.0':
    FILES += ['Ketvoice-2.8.0-Quest.apk', 'phone-player.png', 'phone-equalizer.png',
        'quest-player.png', 'quest-equalizer.png', 'quest-wifi.png', 'quest-consent.png']
EXPECTED_APK = {
    '2.6.0': '8636778748a8c956db8b3fb3286d3bc5e07b743e0310d65cd2d1a1c32c8998d1',
    '2.6.1': 'be3225dfbe3abb95f548741ed6eb7a1597612e2bb7b24fe306a88a0bf1736676',
    '2.7.0': '4fe2929308f5c6f74dbf6cc64f528ced959e035fd05ea323aff53f03408559bc',
    '2.7.1': '38bd77b76fe0be32413b5c4f3647a5a3c4c5b8b03774ca8d32e007033c7cb2cc',
    '2.8.0': 'ab9d4d0bf350d44e835a7782afdf07151c3a7f72f53b4e5214d70c066e09d142',
}[VERSION]
QUEST_APK = {
    '2.7.0': ('Ketvoice-2.7.0-Quest.apk', '7dad32c00f26e7508869d683e9338aa9eb7a73f795e42c418f4d13c2a1a91db6'),
    '2.8.0': ('Ketvoice-2.8.0-Quest.apk', '1cd2ccb9adce8a787372da4a5e285382753b3baa4346458b10490587d7a961c6'),
}.get(VERSION)

def git(*argv):
    return subprocess.check_output(['git', *argv], cwd=ROOT).decode().strip()

if git('status', '--porcelain'):
    raise SystemExit('Commit the validated source before publishing.')
commit = git('rev-parse', 'HEAD')
if commit != git('rev-parse', 'origin/main'):
    raise SystemExit('Push the source commit first.')
for name in FILES:
    if not (STAGE / name).is_file():
        raise SystemExit('Missing release asset: ' + name)
if hashlib.sha256((STAGE / FILES[0]).read_bytes()).hexdigest() != EXPECTED_APK:
    raise SystemExit('APK checksum does not match the validated binary.')
if QUEST_APK and hashlib.sha256((STAGE / QUEST_APK[0]).read_bytes()).hexdigest() != QUEST_APK[1]:
    raise SystemExit('Quest APK checksum does not match the validated binary.')

token = os.environ.get('GITHUB_TOKEN', '')
if not token:
    env = dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never')
    result = subprocess.run(['git', 'credential', 'fill'], cwd=ROOT, env=env,
        input=b'protocol=https\nhost=github.com\n\n', capture_output=True, check=True)
    fields = dict(line.split('=', 1) for line in result.stdout.decode().splitlines() if '=' in line)
    token = fields.get('password', '')
if not token:
    raise SystemExit('No GitHub credential available.')

def api(path, method='GET', data=None, content_type='application/json'):
    url = path if path.startswith('https://') else 'https://api.github.com' + path
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    headers = {'Authorization': 'Bearer ' + token, 'User-Agent': 'Ketvoice-release',
        'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
    if body is not None:
        headers['Content-Type'] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.load(response)

releases = api(f'/repos/{REPO}/releases?per_page=100')
release = next((r for r in releases if r['tag_name'] == TAG), None)
if release and not release['draft']:
    raise SystemExit('Release already published; refusing to overwrite it.')
if not release:
    release = api(f'/repos/{REPO}/releases', 'POST', {
        'tag_name': TAG, 'target_commitish': commit, 'name': 'Ketvoice ' + VERSION,
        'body': (STAGE / 'RELEASE-NOTES.md').read_text(encoding='utf-8'),
        'draft': True, 'prerelease': False,
    })

existing = {a['name']: a for a in release['assets']}
upload = release['upload_url'].split('{')[0]
for name in FILES:
    data = (STAGE / name).read_bytes()
    digest = 'sha256:' + hashlib.sha256(data).hexdigest()
    asset = existing.get(name)
    if not asset:
        asset = api(upload + '?name=' + urllib.parse.quote(name), 'POST', data,
            mimetypes.guess_type(name)[0] or 'application/octet-stream')
    if asset.get('state') != 'uploaded' or asset.get('size') != len(data):
        raise SystemExit('Asset verification failed: ' + name)
    if asset.get('digest') and asset['digest'] != digest:
        raise SystemExit('Remote checksum mismatch: ' + name)
    print('Verified asset:', name, len(data), flush=True)

if args.publish:
    release = api(f'/repos/{REPO}/releases/{release["id"]}', 'PATCH', {
        'draft': False, 'make_latest': 'false' if VERSION == '2.7.1' else 'true'})
print('Published' if not release['draft'] else 'Draft ready:', release['html_url'])
