"""Publish only verified, signed release APKs; never overwrite a public tag/release.

Requires staged RELEASE-NOTES.md, validation.json, SHA256SUMS.txt and both APKs.
Example: --version 3.0.0-Beta3 --tag v3.0.0-Beta3-rebuild.1 --version-code 25
"""
import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import subprocess
import urllib.parse
import urllib.request
from verify_apk import verify

ROOT = Path(__file__).resolve().parents[1]
REPO = 'Tysnnn23/Ketvoice'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT).decode().strip()

def digest(data):
    return hashlib.sha256(data).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--version-code', required=True, type=int)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--allow-incomplete-device-validation', action='store_true')
    args = parser.parse_args()
    if not args.tag.startswith('v' + args.version) or '/' in args.tag or '..' in args.tag:
        raise SystemExit('Tag must identify this version; no paths allowed')
    stage = ROOT / 'release-assets' / args.tag.removeprefix('v')
    if git('status', '--porcelain'):
        raise SystemExit('Commit all validated sources first')
    git('fetch', 'origin', 'main')
    commit = git('rev-parse', 'HEAD')
    if commit != git('rev-parse', 'origin/main'):
        raise SystemExit('Push this source commit to main first')
    artifact_version = args.tag.removeprefix('v')
    apks = [f'Ketvoice-{artifact_version}.apk', f'Ketvoice-{artifact_version}-Quest.apk']
    files = apks + ['RELEASE-NOTES.md', 'validation.json', 'SHA256SUMS.txt']
    validation = json.loads((stage / 'validation.json').read_text(encoding='utf-8'))
    if validation.get('source_commit') != commit:
        raise SystemExit('Validation record is for different sources')
    override = validation.get('publication_override')
    if args.allow_incomplete_device_validation and not (
        isinstance(override, dict) and override.get('authorized_by_user') is True and override.get('reason')
    ):
        raise SystemExit('An explicit user-authorized publication override must be recorded')
    if override and not args.allow_incomplete_device_validation:
        raise SystemExit('This release requires --allow-incomplete-device-validation')
    scan = validation.get('credential_scan')
    smoke = validation.get('release_install_smoke')
    if not isinstance(scan, dict) or scan.get('findings') != 0 or not scan.get('entries'):
        raise SystemExit('A passing credential scan must be recorded')
    if (not isinstance(smoke, dict) or any(
        not smoke.get(flavor, {}).get('installed_and_launched') for flavor in ('mobile', 'quest')
    )) and not args.allow_incomplete_device_validation:
        raise SystemExit('Both signed release APKs require install/launch checks')
    for flavor in ('mobile', 'quest'):
        for suite, minimum in (('jvm', 117), ('android_15_emulator_compose', 19)):
            result = validation['tests'][flavor][suite]
            incomplete = result.get('tests', 0) < minimum
            failed = any(result.get(key, 0) for key in ('failures', 'errors', 'skipped'))
            if failed or (incomplete and (suite == 'jvm' or not args.allow_incomplete_device_validation)):
                raise SystemExit('Incomplete or failed validation suite')
        if any(validation['lint'][flavor].get(level, 0) for level in ('Error', 'Fatal')):
            raise SystemExit('Release lint errors')
    for index, name in enumerate(apks):
        checked = verify(stage / name, args.build_tools, args.version, args.version_code, quest=index == 1)
        entry = next(a for a in validation['apks'] if a['name'] == name)
        if checked != entry:
            raise SystemExit('APK no longer matches its validation record: ' + name)
    checksums = {}
    for line in (stage / 'SHA256SUMS.txt').read_text(encoding='utf-8').splitlines():
        sha, name = line.split('  ', 1)
        checksums[name] = sha
    if set(checksums) != set(files) - {'SHA256SUMS.txt'}:
        raise SystemExit('Checksum list must cover exactly the release payload')
    for name, expected in checksums.items():
        if digest((stage / name).read_bytes()) != expected:
            raise SystemExit('Staging checksum mismatch: ' + name)

    token = os.environ.get('GITHUB_TOKEN', '')
    if not token:
        env = dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never')
        response = subprocess.run(['git', 'credential', 'fill'], input=b'protocol=https\nhost=github.com\n\n',
                                  env=env, capture_output=True, check=True)
        fields = dict(line.split('=', 1) for line in response.stdout.decode().splitlines() if '=' in line)
        token = fields.get('password', '')
    if not token:
        raise SystemExit('No authorized GitHub credential available')

    def api(path, method='GET', data=None, content_type='application/json'):
        url = path if path.startswith('https://') else 'https://api.github.com' + path
        headers = {'Authorization': 'Bearer ' + token, 'User-Agent': 'Ketvoice-release',
                   'Accept': 'application/vnd.github+json', 'Content-Type': content_type}
        body = json.dumps(data).encode() if isinstance(data, dict) else data
        with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers, method=method), timeout=300) as r:
            return json.load(r)

    releases = api(f'/repos/{REPO}/releases?per_page=100')
    release = next((r for r in releases if r['tag_name'] == args.tag), None)
    if release and not release['draft']:
        raise SystemExit('Public release already exists; refusing to overwrite')
    if not release:

        if git('tag', '--list', args.tag):
            raise SystemExit('Tag already exists; use a fresh rebuild tag')
        release = api(f'/repos/{REPO}/releases', 'POST', {
            'tag_name': args.tag, 'target_commitish': commit,
            'name': 'Ketvoice ' + args.version + (' — rebuilt APKs' if 'rebuild' in args.tag.casefold() else ''),
            'body': (stage / 'RELEASE-NOTES.md').read_text(encoding='utf-8'), 'draft': True,
            'prerelease': any(marker in args.tag.casefold() for marker in ('beta', 'alpha', 'rc', 'rebuild'))})
    elif release['target_commitish'] != commit:
        raise SystemExit('Existing draft belongs to a different source commit')
    existing = {a['name']: a for a in release['assets']}
    if set(existing) - set(files):
        raise SystemExit('Unexpected draft assets; refusing to publish')
    for name in files:
        data = (stage / name).read_bytes()
        asset = existing.get(name)
        if asset is None:
            asset = api(release['upload_url'].split('{')[0] + '?name=' + urllib.parse.quote(name),
                        'POST', data, mimetypes.guess_type(name)[0] or 'application/octet-stream')
        if asset.get('state') != 'uploaded' or asset['size'] != len(data):
            raise SystemExit('Upload size/state mismatch: ' + name)
        if asset.get('digest') and asset['digest'] != 'sha256:' + digest(data):
            raise SystemExit('Remote checksum mismatch: ' + name)

        request = urllib.request.Request(asset['url'], headers={
            'Authorization': 'Bearer ' + token, 'Accept': 'application/octet-stream', 'User-Agent': 'Ketvoice-release'})
        with urllib.request.urlopen(request, timeout=300) as response:
            downloaded = hashlib.sha256()
            while chunk := response.read(1024 * 1024):
                downloaded.update(chunk)
        if downloaded.hexdigest() != digest(data):
            raise SystemExit('Downloaded bytes mismatch: ' + name)
        print('Upload and download verified:', name, flush=True)
    if args.publish:
        release = api(f'/repos/{REPO}/releases/{release["id"]}', 'PATCH', {'draft': False, 'make_latest': 'false'})
    print('Published:' if not release['draft'] else 'Draft ready:', release['html_url'])

if __name__ == '__main__':
    main()
