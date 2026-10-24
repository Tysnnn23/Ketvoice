"""Stage Beta3 only after both flavor suites, lint and signed APK checks pass.

Run from the repository root with --build-tools <SDK/build-tools/36.0.0>.
Release-install smoke and credential scan results are recorded separately.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
from verify_apk import verify

ROOT = Path(__file__).resolve().parents[1]
REBUILD = '3.0.0-Beta3-rebuild.1'
VERSION = '3.0.0-Beta3'


def aggregate(paths):
    counts = Counter()
    paths = list(paths)
    if not paths:
        raise ValueError('Missing test results')
    for path in paths:
        root = ET.parse(path).getroot()
        counts.update({key: int(root.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')})
    if counts['failures'] or counts['errors'] or counts['skipped']:
        raise ValueError('Tests failed or were skipped')
    return dict(counts)


def checksums(stage):
    names = [f'Ketvoice-{REBUILD}.apk', f'Ketvoice-{REBUILD}-Quest.apk', 'RELEASE-NOTES.md', 'validation.json']
    (stage / 'SHA256SUMS.txt').write_text(''.join(
        hashlib.sha256((stage / name).read_bytes()).hexdigest() + '  ' + name + '\n'
        for name in names), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--stamp', action='store_true')
    args = parser.parse_args()
    stage = ROOT / 'release-assets' / REBUILD
    if args.stamp:
        if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip():
            raise ValueError('Commit the validated source first')
        path = stage / 'validation.json'
        validation = json.loads(path.read_text(encoding='utf-8'))
        validation['source_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()
        path.write_text(json.dumps(validation, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        checksums(stage)
        print('Stamped source commit:', validation['source_commit'])
        return

    validation = {'version': VERSION, 'versionCode': 25, 'tag': 'v' + REBUILD,
                  'source_base_commit': '40ed0f3adfa8dc47b02174a00b9b132c1221b580',
                  'source_commit': None, 'tests': {}, 'lint': {}, 'apks': []}
    for flavor in ('mobile', 'quest'):
        jvm = aggregate((ROOT / 'app/build/test-results' / f'test{flavor.title()}DebugUnitTest').glob('*.xml'))
        android = aggregate((ROOT / 'app/build/outputs/androidTest-results/connected/debug/flavors' / flavor).glob('*.xml'))
        if jvm['tests'] < 117 or android['tests'] < 19:
            raise ValueError('Incomplete expected test coverage')
        validation['tests'][flavor] = {'jvm': jvm, 'android_15_emulator_compose': android}
        issues = ET.parse(ROOT / f'app/build/reports/lint-results-{flavor}Release.xml').getroot()
        lint = dict(Counter(issue.get('severity') for issue in issues.iter('issue')))
        if any(lint.get(level, 0) for level in ('Error', 'Fatal')):
            raise ValueError('Release lint errors')
        validation['lint'][flavor] = lint

    stage.mkdir(parents=True, exist_ok=True)
    for flavor, suffix in (('mobile', ''), ('quest', '-Quest')):
        source = ROOT / f'app/build/outputs/apk/{flavor}/release/app-{flavor}-release.apk'
        verify(source, args.build_tools, VERSION, 25, quest=flavor == 'quest')
        dest = stage / f'Ketvoice-{REBUILD}{suffix}.apk'
        shutil.copyfile(source, dest)
        validation['apks'].append(verify(dest, args.build_tools, VERSION, 25, quest=flavor == 'quest'))
    validation.update({
        'signing_identity': 'existing external Android Debug development certificate; not a store release',
        'release_install_smoke': 'pending',
        'credential_scan': 'pending',
        'python_apk_gate_unit_tests': {'tests': 8, 'note': 'SDK subprocess responses mocked'},
        'translations': {'interface_languages': 11, 'messages_per_non_french_language': 749},
        'limitations': ['Android 15 emulator only; no physical phone or Quest tested',
                        'No authenticated owner-session upload/edit',
                        'No full live provider/Spotify playback or download',
                        'Android 12 permission path declared but not run on an Android 12 device',
                        'No guarantee of zero bugs; no backend changes or Ketogether'],
    })
    shutil.copyfile(ROOT / f'docs/releases/v{REBUILD}.md', stage / 'RELEASE-NOTES.md')
    (stage / 'validation.json').write_text(json.dumps(validation, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    checksums(stage)
    print('Staged signed mobile and Quest APKs, notes, validation and checksums:', stage)


if __name__ == '__main__':
    main()
