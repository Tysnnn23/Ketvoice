"""Verify a downloaded unsigned IPA against its build metadata; not an installation test."""
import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import struct
import zipfile


def verify(ipa: Path, metadata_path: Path, source_commit: str):
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
    if metadata.get('sha256') != digest or metadata.get('source_commit') != source_commit:
        raise ValueError('Downloaded IPA checksum or source identity mismatch')
    if metadata.get('signed') is not False or metadata.get('installable_without_resigning') is not False:
        raise ValueError('Unexpected signing/installability claim')
    with zipfile.ZipFile(ipa) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError('Duplicate or corrupt ZIP entries')
        root = 'Payload/Ketvoice.app/'
        if any(not name.startswith(root) or '..' in PurePosixPath(name).parts or '\\' in name for name in names):
            raise ValueError('Unsafe or unexpected ZIP layout')
        if any('_CodeSignature' in PurePosixPath(name).parts or name.endswith(('.mobileprovision', '.p12', '.p8')) for name in names):
            raise ValueError('Signing/provisioning material in unsigned artifact')
        info = plistlib.loads(archive.read(root + 'Info.plist'))
        if info.get('CFBundleIdentifier') != 'com.ketvoice.ios' or info.get('DTPlatformName') != 'iphoneos':
            raise ValueError('Wrong app identity or simulator product')
        executable = info.get('CFBundleExecutable', '')
        if not executable or PurePosixPath(executable).name != executable:
            raise ValueError('Invalid executable identity')
        header = archive.read(root + executable)[:8]
        if len(header) != 8 or struct.unpack('<II', header) != (0xFEEDFACF, 0x0100000C):
            raise ValueError('Wrong executable architecture')
        if info.get('UIBackgroundModes') != ['audio']:
            raise ValueError('Background audio capability missing')
        locales = {name.removeprefix(root).split('/')[0].removesuffix('.lproj') for name in names
                   if name.endswith('.lproj/Localizable.strings')}
        if locales != {'fr', 'en', 'hi', 'ja', 'zh', 'ru', 'it', 'es', 'de', 'pt', 'ko'}:
            raise ValueError('Missing native locale catalog')
        config = json.loads(archive.read(root + 'PublicCatalog.json'))
        body = config['publishableKey'].split('.')[1]
        public_key = json.loads(base64.urlsafe_b64decode(body + '=' * (-len(body) % 4)))
        if public_key.get('role') != 'anon':
            raise ValueError('Privileged catalog credential in app')
        if root + 'PrivacyInfo.xcprivacy' not in names:
            raise ValueError('Privacy manifest missing')
    return {'artifact': ipa.name, 'sha256': digest, 'source_commit': source_commit,
            'zip_crc': 'passed', 'architecture': 'arm64-device', 'locale_catalogs': 11,
            'background_audio_capability': 'present', 'catalog_key_role': 'anon',
            'provisioning_profile': 'absent', 'installable_without_resigning': False,
            'physical_iphone_validation': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ipa', type=Path)
    parser.add_argument('metadata', type=Path)
    parser.add_argument('--source-commit', required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.ipa, args.metadata, args.source_commit), indent=2))
