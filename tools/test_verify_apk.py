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

    def test_upper_case_or_colon_digest_is_the_same_certificate(self):
        spaced = ':'.join(CERTIFICATE[i:i + 2] for i in range(0, 64, 2)).upper()
        with self.tools(certificate=spaced):
            self.assertEqual(CERTIFICATE, self.check()['certificate_sha256'])

    def test_build_tools_37_lines_per_scheme(self):
        lines = 'V2 Signer: certificate SHA-256 digest: {0}\nV3 Signer: certificate SHA-256 digest: {0}'.format(CERTIFICATE)
        with patch('verify_apk.run_tool', side_effect=lambda path, *a: lines if 'apksigner' in path.name else
                   self.tools_output(path)):
            self.assertEqual(CERTIFICATE, self.check()['certificate_sha256'])

    def tools_output(self, path):
        if 'zipalign' in path.name:
            return 'Verification successful'
        return "package: name='com.ketvoice.app' versionCode='25' versionName='3.0.0-Beta3'\nsdkVersion:'31'\ntargetSdkVersion:'36'\n"

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

    # ---- Ketvoice TV (1.0.0): com.ketvoice.tv, its own version line, leanback, no touchscreen required.

    # What aapt 36.0.0 prints for a leanback manifest (an APK linked with aapt2 from such a manifest).
    TV_FEATURES = ("leanback-launchable-activity: name='com.ketvoice.app.tv.TvActivity'  label='Ketvoice TV' icon='' "
                   "banner='res/drawable-xhdpi-v4/tv_banner.png'\n"
                   "feature-group: label=''\n"
                   "  uses-feature-not-required: name='android.hardware.faketouch'\n"
                   "  uses-feature-not-required: name='android.hardware.touchscreen'\n"
                   "  uses-feature: name='android.software.leanback'\n")

    def tv_tools(self, package='com.ketvoice.tv', features=TV_FEATURES, label='Ketvoice TV', min_sdk=28,
                 certificate=CERTIFICATE):
        def run(path, *args):
            if 'apksigner' in path.name:
                return 'Signer #1 certificate SHA-256 digest: ' + certificate
            if 'zipalign' in path.name:
                return 'Verification successful'
            return (f"package: name='{package}' versionCode='1' versionName='1.0.0'\n"
                    f"sdkVersion:'{min_sdk}'\ntargetSdkVersion:'36'\n" +
                    (f"application-label:'{label}'\n" if label else '') + features)
        return patch('verify_apk.run_tool', side_effect=run)

    def check_tv(self, edition='tv'):
        return verify(self.apk, Path(self.directory.name), '1.0.0', 1, edition=edition)

    def test_tv_release_produces_its_own_record(self):
        self.add_libs('arm64-v8a', 'armeabi-v7a')
        with self.tv_tools():
            report = self.check_tv()
        self.assertEqual('tv', report['edition'])
        self.assertEqual('com.ketvoice.tv', report['package'])
        self.assertEqual(CERTIFICATE, report['certificate_sha256'])
        self.assertEqual(1, report['versionCode'])

    def test_tv_and_phone_apks_never_pass_as_each_other(self):
        # A phone APK (com.ketvoice.app) renamed Ketvoice-TV-*.apk, and the TV APK passed off as mobile or Lite.
        with self.tv_tools(package='com.ketvoice.app'), self.assertRaises(ValueError):
            self.check_tv()
        for edition in ('mobile', 'lite', 'quest'):
            with self.tv_tools(), self.assertRaises(ValueError):
                self.check_tv(edition)

    def test_tv_needs_the_leanback_launcher_and_feature(self):
        no_activity = self.TV_FEATURES.split('\n', 1)[1]
        no_feature = self.TV_FEATURES.replace("  uses-feature: name='android.software.leanback'\n", '')
        for features in (no_activity, no_feature):
            with self.tv_tools(features=features), self.assertRaises(ValueError):
                self.check_tv()
        # leanback declared as optional still counts as declared.
        optional = self.TV_FEATURES.replace("uses-feature: name='android.software.leanback'",
                                            "uses-feature-not-required: name='android.software.leanback'")
        with self.tv_tools(features=optional):
            self.assertEqual('tv', self.check_tv()['edition'])

    def test_tv_never_requires_a_touchscreen(self):
        required = self.TV_FEATURES.replace("uses-feature-not-required: name='android.hardware.touchscreen'",
                                            "uses-feature: name='android.hardware.touchscreen'")
        implied = (self.TV_FEATURES.replace("  uses-feature-not-required: name='android.hardware.touchscreen'\n", '') +
                   "  uses-implied-feature: name='android.hardware.touchscreen' reason='default feature for all apps'\n")
        faketouch = self.TV_FEATURES.replace("uses-feature-not-required: name='android.hardware.faketouch'",
                                             "uses-feature: name='android.hardware.faketouch'")
        undeclared = self.TV_FEATURES.replace("  uses-feature-not-required: name='android.hardware.touchscreen'\n", '')
        for features in (required, implied, faketouch, undeclared):
            with self.tv_tools(features=features), self.assertRaises(ValueError):
                self.check_tv()

    def test_tv_ships_arm_libraries_only(self):
        self.add_libs('arm64-v8a', 'armeabi-v7a', 'x86')
        with self.tv_tools(), self.assertRaises(ValueError):
            self.check_tv()

    def test_tv_sdk_label_and_signature(self):
        for kwargs in ({'min_sdk': 31}, {'label': 'Ketvoice'}, {'label': None}, {'certificate': '0' * 64}):
            with self.tv_tools(**kwargs), self.assertRaises(ValueError):
                self.check_tv()

    def test_tv_file_name_maps_to_the_tv_edition(self):
        self.assertEqual('tv', edition_of('Ketvoice-TV-1.0.0.apk'))
        self.assertEqual('mobile', edition_of('Ketvoice-9.1.1.apk'))

    def test_truncated_zip_rejected(self):
        self.apk.write_bytes(self.apk.read_bytes()[:50])
        with self.assertRaises(zipfile.BadZipFile):
            self.check()


if __name__ == '__main__':
    unittest.main()
