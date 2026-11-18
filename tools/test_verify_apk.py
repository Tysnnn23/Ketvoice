"""Unit tests for the release gate (SDK tools mocked, no signing key needed)."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile
from verify_apk import CERTIFICATE, edition_of, verify


class ApkGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'Ketvoice.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for name in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'):
                archive.writestr(name, b'test fixture')

    def tools(self, certificate=CERTIFICATE, quest=False, version='3.0.0-Beta3', code=25, debug=False, label=None,
              min_sdk=None):
        min_sdk = min_sdk or (32 if quest else 30 if label == 'Ketvoice Lite' else 31)
        def run(path, *args):
            if 'apksigner' in path.name:
                return 'Signer #1 certificate SHA-256 digest: ' + certificate
            if 'zipalign' in path.name:
                return 'Verification successful'
            return (f"package: name='com.ketvoice.app' versionCode='{code}' versionName='{version}'\n"
                    f"sdkVersion:'{min_sdk}'\ntargetSdkVersion:'{34 if quest else 36}'\n" +
                    (f"application-label:'{label}'\n" if label else '') +
                    ('application-debuggable' if debug else ''))
        return patch('verify_apk.run_tool', side_effect=run)

    def check(self, **kwargs):
        return verify(self.apk, Path(self.directory.name), '3.0.0-Beta3', 25, **kwargs)

    def test_signed_release_produces_integrity_record(self):
        with self.tools():
            report = self.check()
        self.assertEqual(64, len(report['sha256']))
        self.assertEqual(CERTIFICATE, report['certificate_sha256'])
        self.assertFalse(report['debuggable'])

    def test_unsigned_rejected_even_with_valid_zip(self):
        with patch('verify_apk.run_tool', side_effect=ValueError('Missing META-INF/MANIFEST.MF')):
            with self.assertRaises(ValueError):
                self.check()

    def test_wrong_certificate_rejected(self):
        with self.tools(certificate='0' * 64), self.assertRaises(ValueError):
            self.check()

    def test_debug_output_rejected(self):
        with self.tools(debug=True), self.assertRaises(ValueError):
            self.check()

    def test_stale_version_rejected(self):
        with self.tools(code=24), self.assertRaises(ValueError):
            self.check()

    def test_quest_must_use_quest_manifest(self):
        with self.tools(), self.assertRaises(ValueError):
            self.check(quest=True)
        with self.tools(quest=True):
            self.assertEqual('passed', self.check(quest=True)['signature'])

    def test_failed_alignment_rejected(self):
        normal = self.tools()
        with normal as mocked:
            real_effect = mocked.side_effect
            def reject(path, *args):
                if 'zipalign' in path.name:
                    raise ValueError('misaligned')
                return real_effect(path, *args)
            mocked.side_effect = reject
            with self.assertRaises(ValueError):
                self.check()

    def add_libs(self, *abis):
        with zipfile.ZipFile(self.apk, 'a') as archive:
            for abi in abis:
                archive.writestr(f'lib/{abi}/libtensorflowlite_jni.so', b'elf')

    def test_lite_needs_its_own_label_and_arm64_only(self):
        self.add_libs('arm64-v8a')
        with self.tools(label='Ketvoice Lite'):
            report = self.check(edition='lite')
        self.assertEqual('lite', report['edition'])
        self.assertEqual('com.ketvoice.app', report['package'])
        # A Lite APK built for Android 12 only (or a mobile one relabelled) is refused.
        with self.tools(label='Ketvoice Lite', min_sdk=31), self.assertRaises(ValueError):
            self.check(edition='lite')
        # A mobile APK renamed -Lite (or an unlabelled one) is refused.
        for label in ('Ketvoice', None):
            with self.tools(label=label), self.assertRaises(ValueError):
                self.check(edition='lite')

    def test_lite_with_other_abis_rejected(self):
        self.add_libs('arm64-v8a', 'x86_64')
        with self.tools(label='Ketvoice Lite'), self.assertRaises(ValueError):
            self.check(edition='lite')

    def test_mobile_and_quest_never_pass_as_each_other_or_lite(self):
        with self.tools(label='Ketvoice Lite'), self.assertRaises(ValueError):
            self.check()
        with self.tools(label='Ketvoice'):
            self.assertNotIn('edition', self.check())
        with self.tools(quest=True, label='Ketvoice Quest'):
            self.assertNotIn('edition', self.check(quest=True))
        with self.tools(label='Ketvoice'), self.assertRaises(ValueError):
            self.check(quest=True, edition='lite')

    def test_edition_follows_the_published_file_name(self):
        self.assertEqual('mobile', edition_of('Ketvoice-6.1.0.apk'))
        self.assertEqual('quest', edition_of('Ketvoice-6.1.0-Quest.apk'))
        self.assertEqual('lite', edition_of('Ketvoice-6.1.0-Lite.apk'))

    def test_truncated_zip_rejected(self):
        self.apk.write_bytes(self.apk.read_bytes()[:50])
        with self.assertRaises(zipfile.BadZipFile):
            self.check()


if __name__ == '__main__':
    unittest.main()
