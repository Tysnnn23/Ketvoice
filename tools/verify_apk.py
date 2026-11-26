"""Fail closed on APK corruption, unsigned output, wrong identity or misalignment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

CERTIFICATE = 'ae25a19cee8ac781f30de4c933541257fdd46a6ea7fca6f7ab021f68221f37a9'
# The APKs: (minSdk, targetSdk) and the launcher label each must carry, and their package.
EDITIONS = {
    'mobile': {'sdk': ('31', '36'), 'label': 'Ketvoice'},
    'quest': {'sdk': ('32', '34'), 'label': 'Ketvoice Quest'},
    # Ketvoice Lite (6.1): the phone app, lighter; same package and signature so it replaces mobile in place.
    # 6.5: Lite also installs on Android 11 (minSdk 30).
    'lite': {'sdk': ('30', '36'), 'label': 'Ketvoice Lite', 'abis': {'arm64-v8a'}},
    # Ketvoice TV (1.0.0): its own package and version line (tv-v<version> releases), the same signing key.
    # Android TV 8+ / Google TV, 32- and 64-bit ARM, started from the leanback launcher with the remote.
    'tv': {'sdk': ('28', '36'), 'label': 'Ketvoice TV', 'abis': {'arm64-v8a', 'armeabi-v7a'},
           'package': 'com.ketvoice.tv'},
}
PACKAGE = 'com.ketvoice.app'


def run_tool(path, *args):
    result = subprocess.run([str(path), *map(str, args)], capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=180)
    if result.returncode:
        raise ValueError(f'{path.name} rejected the APK: {result.stdout}{result.stderr}')
    return result.stdout


def edition_of(name):
    """The edition a published file name stands for: Ketvoice-<v>.apk, -Quest.apk, -Lite.apk or Ketvoice-TV-<v>.apk."""
    if name.startswith('Ketvoice-TV-'):
        return 'tv'
    if name.endswith('-Quest.apk'):
        return 'quest'
    if name.endswith('-Lite.apk'):
        return 'lite'
    return 'mobile'


def verify(path, build_tools, version, version_code, quest=False, certificate=CERTIFICATE, edition=None):
    path, build_tools = Path(path), Path(build_tools)
    edition = edition or ('quest' if quest else 'mobile')
    if edition not in EDITIONS or (quest and edition != 'quest'):
        raise ValueError('Unknown edition ' + str(edition))
    expected = EDITIONS[edition]
    if not path.is_file() or path.suffix != '.apk':
        raise ValueError('Expected one installable .apk file')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        required = {'AndroidManifest.xml', 'classes.dex', 'resources.arsc'}
        if not required <= set(names):
            raise ValueError('Missing APK entries')
        if archive.testzip() is not None:
            raise ValueError('APK ZIP CRC check failed')
    abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.count('/') >= 2}
    if 'abis' in expected and not abis <= expected['abis']:
        raise ValueError('Unexpected native ABIs for ' + edition + ': ' + ', '.join(sorted(abis)))
    suffix = '.bat' if os.name == 'nt' else ''
    signature = run_tool(build_tools / ('apksigner' + suffix), 'verify', '--verbose', '--print-certs', path)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', signature)
    if certs != [certificate]:
        raise ValueError('Unexpected signing certificate (cannot update existing Ketvoice)')
    run_tool(build_tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign'), '-c', '-P', '16', '4', path)
    badging = run_tool(build_tools / ('aapt.exe' if os.name == 'nt' else 'aapt'), 'dump', 'badging', path)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (expected.get('package', PACKAGE), str(version_code), version):
        raise ValueError('Wrong package name, versionName or versionCode')
    if 'application-debuggable' in badging:
        raise ValueError('A debug build must not be distributed as a release')
    min_sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not min_sdk or not target or (min_sdk[1], target[1]) != expected['sdk']:
        raise ValueError('Unexpected minimum/target SDK')
    # The default launcher label tells the editions apart (mobile and Lite share package and SDK levels).
    label = re.search(r"^application-label:'([^']*)'", badging, re.M)
    if (edition in ('lite', 'tv') or label) and (not label or label[1] != expected['label']):
        raise ValueError('Wrong launcher label for the ' + edition + ' APK')
    if edition == 'tv':
        tv_manifest(badging)
    record = {'name': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'bytes': path.stat().st_size, 'certificate_sha256': certificate,
              'package': package[1], 'version': version, 'versionCode': version_code,
              'zip_crc': 'passed', 'signature': 'passed', 'alignment_16kb': 'passed',
              'debuggable': False}
    if edition in ('lite', 'tv'):
        # Only the newer APKs carry the field: mobile and Quest records stay identical to earlier releases'.
        record['edition'] = edition
    return record


def tv_manifest(badging):
    """A TV APK starts from the leanback launcher, declares leanback and never requires a touchscreen."""
    if not re.search(r"^leanback-launchable-activity: name='[^']+'", badging, re.M):
        raise ValueError('The TV APK has no leanback launcher activity')
    if not re.search(r"^\s*uses-feature(-not-required)?: name='android\.software\.leanback'", badging, re.M):
        raise ValueError('The TV APK does not declare android.software.leanback')
    for feature in ('touchscreen', 'faketouch'):
        if re.search(r"^\s*uses-(implied-)?feature: name='android\.hardware\." + feature + "'", badging, re.M):
            raise ValueError('The TV APK requires android.hardware.' + feature)
    if not re.search(r"^\s*uses-feature-not-required: name='android\.hardware\.touchscreen'", badging, re.M):
        raise ValueError('The TV APK must declare android.hardware.touchscreen as not required')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--quest', action='store_true')
    parser.add_argument('--edition', choices=sorted(EDITIONS), help='default: from the file name')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    edition = args.edition or ('quest' if args.quest else edition_of(args.apk.name))
    report = verify(args.apk, args.build_tools, args.version, args.version_code, edition=edition)
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))
