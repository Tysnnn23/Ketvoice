"""Host-side packaging/resource checks. These do NOT compile or execute Swift/iOS."""
import hashlib
import json
from pathlib import Path
import plistlib
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from package_unsigned import package
from sync_android_resources import RESOURCES, expected, quote


class ResourceTests(unittest.TestCase):
    def test_generated_catalog_matches_android(self):
        for path, content in expected().items():
            self.assertEqual((RESOURCES / path).read_text(encoding="utf-8"), content, path)

    def test_eleven_localizations(self):
        self.assertEqual(len([path for path in expected() if path.endswith("Localizable.strings")]), 11)

    def test_apple_string_escaping(self):
        self.assertEqual(quote('a"b\\c\nd\te'), '"a\\"b\\\\c\\nd\\te"')

    def test_background_mode_is_an_array(self):
        info = plistlib.loads((RESOURCES.parent / "Ketvoice/Info.plist").read_bytes())
        self.assertEqual(info["UIBackgroundModes"], ["audio"])


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "Ketvoice.app"
        self.app.mkdir()
        self.info = {"CFBundleIdentifier": "com.ketvoice.ios", "CFBundleExecutable": "Ketvoice",
                     "DTPlatformName": "iphoneos", "CFBundleShortVersionString": "3.0.7"}
        self.save_info()
        (self.app / "Ketvoice").write_bytes(struct.pack("<III", 0xFEEDFACF, 0x0100000C, 0) + b"fixture-not-a-real-app")
        self.output = self.root / "Ketvoice-unsigned.ipa"

    def save_info(self):
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info))

    def test_packages_payload_and_marks_unsigned(self):
        metadata = package(self.app, self.output)
        self.assertFalse(metadata["signed"])
        self.assertFalse(metadata["installable_without_resigning"])
        self.assertEqual(metadata["sha256"], hashlib.sha256(self.output.read_bytes()).hexdigest())
        with zipfile.ZipFile(self.output) as archive:
            self.assertIn("Payload/Ketvoice.app/Info.plist", archive.namelist())
            self.assertIsNone(archive.testzip())

    def test_rejects_simulator(self):
        self.info["DTPlatformName"] = "iphonesimulator"
        self.save_info()
        with self.assertRaises(ValueError): package(self.app, self.output)

    def test_rejects_x86_executable(self):
        (self.app / "Ketvoice").write_bytes(struct.pack("<III", 0xFEEDFACF, 0x01000007, 0))
        with self.assertRaises(ValueError): package(self.app, self.output)

    def test_rejects_embedded_signing_profile(self):
        (self.app / "embedded.mobileprovision").write_bytes(b"fixture")
        with self.assertRaises(ValueError): package(self.app, self.output)

    def test_rejects_signing_material_in_nested_resource(self):
        resources = self.app / "Resources"
        resources.mkdir()
        (resources / "accidental.p12").write_bytes(b"fixture")
        with self.assertRaises(ValueError): package(self.app, self.output)

    def test_allows_system_symlink_outside_bundle(self):
        with patch.object(Path, "is_symlink", lambda path: path == self.root):
            self.assertFalse(package(self.app, self.output)["signed"])

    def test_rejects_symlink_directory_inside_bundle(self):
        resources = self.app / "Resources"
        resources.mkdir()
        (resources / "example.json").write_text("{}", encoding="utf-8")
        with patch.object(Path, "is_symlink", lambda path: path == resources):
            with self.assertRaises(ValueError): package(self.app, self.output)

    def test_refuses_overwrite(self):
        self.output.write_bytes(b"existing user artifact")
        with self.assertRaises(FileExistsError): package(self.app, self.output)
        self.assertEqual(self.output.read_bytes(), b"existing user artifact")

    def test_rejects_path_in_executable(self):
        self.info["CFBundleExecutable"] = "../Ketvoice"
        self.save_info()
        with self.assertRaises(ValueError): package(self.app, self.output)


if __name__ == "__main__":
    unittest.main()
