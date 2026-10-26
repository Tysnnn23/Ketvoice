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
from audit_binary_strings import inspect, mask_symbol_aq_matches
from render_simulator import select_device
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


class BinaryAuditTests(unittest.TestCase):
    def inspect_fixture(self, payload):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "fixture.bin"
            file.write_bytes(payload)
            return inspect(file)

    def test_classifies_native_swift_symbol_without_disclosing_value(self):
        candidate = b"A" + b"Q" + b"a" * 94
        strings = b"\0_$sNativeSymbol" + candidate + b"\0"
        header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, 2, 1, 24, 0, 0)
        command = struct.pack("<6I", 2, 24, 0, 0, 56, len(strings))
        report = self.inspect_fixture(header + command + strings)
        self.assertEqual(len(report["matches"]), 1)
        self.assertTrue(report["matches"][0]["in_native_swift_symbol"])
        self.assertNotIn(candidate.decode(), json.dumps(report))

    def test_symbol_mask_does_not_mask_literal_sections(self):
        candidate = b"A" + b"Q" + b"a" * 94
        literal = candidate + b"\0"
        symbols = b"\0_$sNativeSymbol" + candidate + b"\0"
        header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, 2, 1, 24, 0, 0)
        command = struct.pack("<6I", 2, 24, 0, 0, 56 + len(literal), len(symbols))
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "fixture.bin"
            original = header + command + literal + symbols
            file.write_bytes(original)
            masked, count = mask_symbol_aq_matches(file)
            self.assertEqual(count, 1)
            self.assertEqual(masked.count(candidate), 1)
            self.assertEqual(file.read_bytes(), original)

    def test_symbol_mask_never_suppresses_github_token_rules(self):
        candidate = b"gh" + b"p_" + b"a" * 32
        symbols = b"\0_$sNativeSymbol" + candidate + b"\0"
        header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, 2, 1, 24, 0, 0)
        command = struct.pack("<6I", 2, 24, 0, 0, 56, len(symbols))
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "fixture.bin"
            file.write_bytes(header + command + symbols)
            masked, count = mask_symbol_aq_matches(file)
            self.assertEqual(count, 0)
            self.assertIn(candidate, masked)

    def test_literal_outside_symbol_table_is_not_a_swift_symbol(self):
        header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, 2, 0, 0, 0, 0)
        report = self.inspect_fixture(header + b"\0A" + b"Q" + b"b" * 94 + b"\0")
        self.assertEqual(len(report["matches"]), 1)
        self.assertFalse(report["matches"][0]["in_native_swift_symbol"])

    def test_non_macho_input_is_rejected(self):
        with self.assertRaises(ValueError): self.inspect_fixture(b"not a Mach-O file")


class SimulatorSelectionTests(unittest.TestCase):
    def test_selects_known_compatible_pair_not_newest_incompatible_runtime(self):
        old = "com.apple.CoreSimulator.SimRuntime.iOS-27-0"
        new = "com.apple.CoreSimulator.SimRuntime.iOS-27-1"
        inventory = {
            "runtimes": [{"identifier": old, "version": "27.0", "isAvailable": True},
                         {"identifier": new, "version": "27.1", "isAvailable": True}],
            "devices": {old: [{"name": "iPhone 17", "isAvailable": True}],
                        new: [{"name": "iPhone Duo", "isAvailable": True}]},
            "devicetypes": [{"name": "iPhone 17", "identifier": "phone17"}, {"name": "iPhone Duo", "identifier": "duo"}],
        }
        device, runtime = select_device(inventory)
        self.assertEqual(device["identifier"], "phone17")
        self.assertEqual(runtime["identifier"], old)


if __name__ == "__main__":
    unittest.main()
