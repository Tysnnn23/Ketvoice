"""Tests for verify_msi.py. The MSI tests build a small real MSI with wixl (msitools) and are skipped without it.

    python3 -m unittest tools/test_verify_msi.py
"""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_msi  # noqa: E402

WXS = """<?xml version="1.0" encoding="utf-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*" Name="Ketvoice" Version="{version}" Manufacturer="Ketvoice" Language="1033"
           UpgradeCode="5319BA3F-92FE-494B-97B3-BDF702D3D01E">
    <Package InstallerVersion="200" Compressed="yes" Comments="{comment}"/>
    <Media Id="1" Cabinet="app.cab" EmbedCab="yes"/>
    <Property Id="LONGVALUE" Value="{long}"/>
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="LocalAppDataFolder"><Directory Id="INSTALLDIR" Name="Ketvoice">
        <Component Id="Main" Guid="2B7A4C1E-1111-4C3B-9D1A-0123456789AB">
          <File Id="Readme" Source="{readme}"/>
          <RegistryValue Root="HKCU" Key="Software\\Ketvoice" Name="installed" Type="integer" Value="1" KeyPath="yes"/>
          <RemoveFolder Id="RemoveInstallDir" On="uninstall"/>
        </Component>
      </Directory></Directory>
    </Directory>
    <Feature Id="All" Level="1"><ComponentRef Id="Main"/></Feature>
  </Product>
</Wix>
"""


@unittest.skipUnless(shutil.which("wixl"), "wixl (msitools) is not installed")
class MsiIdentityTest(unittest.TestCase):
    def build(self, tmp, version):
        readme = Path(tmp) / "readme.txt"
        readme.write_text("Ketvoice\n" * 400)
        wxs = Path(tmp) / "k.wxs"
        wxs.write_text(WXS.format(version=version, readme=readme, comment="x" * 300, long="é" + "v" * 5000))
        msi = Path(tmp) / f"Ketvoice-{version}.msi"
        subprocess.run(["wixl", "-o", str(msi), str(wxs)], check=True, capture_output=True)
        return msi

    def test_ole_parser_matches_msiinfo(self):
        with tempfile.TemporaryDirectory() as tmp:
            msi = self.build(tmp, "1.2.3")
            ours = verify_msi.msi_properties(msi)
            self.assertEqual(ours["ProductVersion"], "1.2.3")
            self.assertEqual(ours["UpgradeCode"], "{5319BA3F-92FE-494B-97B3-BDF702D3D01E}")
            self.assertEqual(ours["LONGVALUE"], "é" + "v" * 5000)
            theirs = verify_msi.msiinfo_properties(msi)
            if theirs is not None:
                self.assertEqual(ours, theirs)

    def test_identity_without_msiinfo_and_wrong_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            msi = self.build(tmp, "1.0.0")
            with mock.patch.object(verify_msi, "msiinfo_properties", return_value=None):
                verify_msi.check_identity(msi, "1.0.0")
                with self.assertRaises(verify_msi.Failure):
                    verify_msi.check_identity(msi, "1.0.1")
                with self.assertRaises(verify_msi.Failure):
                    verify_msi.check_identity(msi, "1.0.0", "{00000000-0000-0000-0000-000000000000}")

    def test_local_release_check(self):
        with tempfile.TemporaryDirectory() as tmp:
            msi = self.build(tmp, "1.0.0")
            (Path(tmp) / "RELEASE-NOTES.md").write_text("# Ketvoice for Windows 1.0.0\n")
            verify_msi.write_sums(tmp, [msi.name, "RELEASE-NOTES.md"])
            sums = Path(tmp) / "SHA256SUMS.txt"
            self.assertEqual(verify_msi.main(["local", "--msi", str(msi), "--sums", str(sums), "--version", "1.0.0"]), 0)
            self.assertEqual(verify_msi.main(["sums-check", str(sums), tmp]), 0)
            with open(msi, "ab") as f:
                f.write(b"\0")
            self.assertEqual(verify_msi.main(["local", "--msi", str(msi), "--sums", str(sums), "--version", "1.0.0"]), 1)
            self.assertEqual(verify_msi.main(["sums-check", str(sums), tmp]), 1)


class SumsAndValidationTest(unittest.TestCase):
    def test_sums_format(self):
        h = "a" * 64
        self.assertEqual(verify_msi.parse_sums(f"{h}  Ketvoice-1.0.0.msi\n{h.upper()} *notes.md\n"),
                         {"Ketvoice-1.0.0.msi": h, "notes.md": h})
        for bad in (f"{h} Ketvoice.msi", f"{h}  ../x", f"{h}  a\n{h}  a", "zz  a"):
            with self.assertRaises(verify_msi.Failure):
                verify_msi.parse_sums(bad)

    def test_validation_record(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "validation.json"
            good = {"version": "1.0.0", "source_commit": "a" * 40, "credential_scan": {"findings": 0},
                    "publication_override": {"authorized_by_user": True, "reason": "standing authorization"}}
            path.write_text(json.dumps(good))
            self.assertEqual(verify_msi.main(["validation", str(path), "1.0.0"]), 0)
            self.assertEqual(verify_msi.main(["validation", str(path), "1.0.1"]), 1)
            for change in ({"credential_scan": {"findings": 2}}, {"source_commit": "abc"},
                           {"publication_override": {"authorized_by_user": False, "reason": "x"}}):
                path.write_text(json.dumps({**good, **change}))
                self.assertEqual(verify_msi.main(["validation", str(path), "1.0.0"]), 1, change)

    def test_stream_names(self):
        # "Property" as MSI stores it: U+4840 then the name packed two characters per unit.
        def pack(name):
            alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._"
            out, s = ["䡀"], name
            while len(s) >= 2:
                out.append(chr(0x3800 + alphabet.index(s[0]) + (alphabet.index(s[1]) << 6))); s = s[2:]
            if s:
                out.append(chr(0x4800 + alphabet.index(s)))
            return "".join(out)
        self.assertEqual(verify_msi.msi_stream_name(pack("Property")), "!Property")
        self.assertEqual(verify_msi.msi_stream_name(pack("_StringPool")), "!_StringPool")


if __name__ == "__main__":
    unittest.main()
