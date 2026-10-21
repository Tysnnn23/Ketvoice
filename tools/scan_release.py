"""Scan source/APK bytes without printing detected credential values."""
import argparse
import base64
import json
import pathlib
import re
import sys
import zipfile
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('targets', nargs='+')
parser.add_argument('--deny-list-json', type=pathlib.Path)
args = parser.parse_args()
denied = set()

def leaves(value):
    if isinstance(value, dict):
        for item in value.values():
            yield from leaves(item)
    elif isinstance(value, list):
        for item in value:
            yield from leaves(item)
    elif isinstance(value, str) and len(value) >= 24:
        yield value

if args.deny_list_json:
    denied.update(leaves(json.loads(args.deny_list_json.read_text(encoding='utf-8-sig'))))
    # Legacy extraction inventories also contain public protocol endpoints.
    # These are not credentials; private provider hosts/URLs remain denied.
    public_hosts = {'open.spotify.com', 'api-partner.spotify.com', 'spclient.wg.spotify.com'}
    denied = {value for value in denied if not (
        value.startswith('https://') and urlsplit(value).hostname in public_hosts
    )}
    denied.update(urlsplit(value).hostname for value in list(denied)
                  if value.startswith('https://') and urlsplit(value).hostname)

patterns = [
    rb'gh[pousr]_[A-Za-z0-9]{30,}',
    rb'github_pat_[A-Za-z0-9_]{40,}',
    rb'AQ[A-Za-z0-9_\-]{90,}',
    rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
]
skip = {'.git', '.gradle', 'build', '.kotlin', 'node_modules', '.tools'}
failures = []
count = 0

def scan(name, data):
    global count
    count += 1
    # DEX uses UTF-8, resources may also contain UTF-16 strings.
    for value in denied:
        if value.encode() in data or value.encode('utf-16le') in data:
            failures.append((name, 'legacy credential'))
            break
    for pattern in patterns:
        if re.search(pattern, data):
            failures.append((name, 'credential pattern'))
    for jwt in re.findall(rb'eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', data):
        try:
            body = jwt.split(b'.')[1]
            payload = json.loads(base64.urlsafe_b64decode(body + b'=' * (-len(body) % 4)))
            if payload.get('role') == 'service_role':
                failures.append((name, 'service-role JWT'))
        except (ValueError, TypeError):
            pass

for target in args.targets:
    path = pathlib.Path(target)
    if path.is_file() and path.suffix == '.apk':
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                scan(path.name + ':' + name, archive.read(name))
    elif path.is_dir():
        for file in path.rglob('*'):
            if file.is_file() and not set(file.relative_to(path).parts) & skip:
                scan(str(file.relative_to(path)), file.read_bytes())
    elif path.is_file():
        scan(path.name, path.read_bytes())
    else:
        raise SystemExit('Missing target: ' + target)

print('Scanned', count, 'files/archive entries;', len(failures), 'findings')
for name, reason in failures:
    print(reason + ': ' + name)
sys.exit(bool(failures))
