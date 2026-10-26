"""Package an already-built arm64 iPhone .app for local re-signing, never claim installability."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import struct
import zipfile


def package(app: Path, output: Path):
    if app.is_symlink() or not app.is_dir() or app.suffix != ".app":
        raise ValueError("Expected a built .app directory, not sources")
    info = plistlib.loads((app / "Info.plist").read_bytes())
    if info.get("CFBundleIdentifier") != "com.ketvoice.ios":
        raise ValueError("Unexpected bundle identity")
    if info.get("DTPlatformName") != "iphoneos":
        raise ValueError("Must be an iPhone device build, not a simulator")
    executable = info.get("CFBundleExecutable", "")
    if not executable or Path(executable).name != executable or "\\" in executable:
        raise ValueError("Invalid executable name")
    header = (app / executable).read_bytes()[:12]
    if len(header) < 12 or struct.unpack("<II", header[:8]) != (0xFEEDFACF, 0x0100000C):
        raise ValueError("Expected a 64-bit arm64 Mach-O device executable")
    if (app / "_CodeSignature").exists() or (app / "embedded.mobileprovision").exists():
        raise ValueError("Expected unsigned input; signing profiles must not be uploaded")
    files = sorted(file for file in app.rglob("*") if file.is_file())
    if any(file.is_symlink() or any(parent.is_symlink() for parent in file.parents if parent != app.parent) for file in files):
        raise ValueError("Symlink input is not supported")
    if output.exists():
        raise FileExistsError("Refusing to overwrite an existing artifact")
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
            for file in files:
                archive.write(file, "Payload/" + app.name + "/" + file.relative_to(app).as_posix())
        metadata = {
            "artifact": output.name,
            "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            "bundle_id": info["CFBundleIdentifier"],
            "version": info.get("CFBundleShortVersionString"),
            "architecture": "arm64",
            "signed": False,
            "installable_without_resigning": False,
            "physical_iphone_validation": False,
        }
        output.with_suffix(".validation.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        output.with_suffix(".sha256").write_text(metadata["sha256"] + "  " + output.name + "\n", encoding="utf-8")
    except Exception:
        output.unlink(missing_ok=True)
        raise
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(package(args.app, args.output), indent=2))
