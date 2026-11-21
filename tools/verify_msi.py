#!/usr/bin/env python3
"""Checks a Ketvoice for Windows release: the MSI's SHA-256 against SHA256SUMS.txt, and the MSI's
ProductVersion and UpgradeCode (with `msiinfo` from msitools when it is installed, else by reading the
MSI's OLE property table here). Used by publish-windows-release.yml before and after publishing.

  verify_msi.py release 1.0.0                      download windows-v1.0.0's MSI and SUMS, check them
  verify_msi.py local --msi X.msi --sums SUMS --version 1.0.0
  verify_msi.py identity X.msi --version 1.0.0     ProductVersion / UpgradeCode only
  verify_msi.py sums-write DIR FILE...             write DIR/SHA256SUMS.txt (sha256sum format)
  verify_msi.py sums-check SUMS DIR                every listed file in DIR matches
  verify_msi.py validation validation.json 1.0.0   the release's validation record

Standard library only; exits 1 with a message on any mismatch.
"""
import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

REPO = "Tysnnn23/Ketvoice"
UPGRADE_CODE = "{5319BA3F-92FE-494B-97B3-BDF702D3D01E}"
SUM_LINE = re.compile(r"^([0-9a-fA-F]{64}) [ *](.+)$")


class Failure(Exception):
    pass


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def parse_sums(text):
    sums = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        m = SUM_LINE.match(line.strip())
        if not m:
            raise Failure(f"bad SHA256SUMS line: {line!r}")
        name = m.group(2).strip()
        if name in sums or "/" in name or "\\" in name:
            raise Failure(f"bad or repeated name in SHA256SUMS: {name!r}")
        sums[name] = m.group(1).lower()
    return sums


def write_sums(directory, names):
    directory = Path(directory)
    lines = [f"{sha256(directory / n)}  {n}\n" for n in names]
    (directory / "SHA256SUMS.txt").write_text("".join(lines), encoding="utf-8", newline="\n")
    return lines


def check_sums(sums_path, directory):
    sums = parse_sums(Path(sums_path).read_text(encoding="utf-8"))
    if not sums:
        raise Failure("SHA256SUMS.txt is empty")
    for name, expected in sums.items():
        f = Path(directory) / name
        if not f.is_file():
            raise Failure(f"{name} is listed in SHA256SUMS.txt but missing")
        actual = sha256(f)
        if actual != expected:
            raise Failure(f"{name}: SHA-256 {actual}, SHA256SUMS.txt says {expected}")
        print(f"sha256 ok  {name}")
    return sums


# ---- MSI (OLE compound file) property table --------------------------------------------------------------

class Cfb:
    """Just enough of the Compound File Binary format to read an MSI's streams."""

    def __init__(self, data):
        if data[:8] != b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1":
            raise Failure("not an MSI (no OLE compound file signature)")
        self.data = data
        self.sector = 1 << struct.unpack_from("<H", data, 0x1E)[0]
        self.mini = 1 << struct.unpack_from("<H", data, 0x20)[0]
        n_fat, first_dir = struct.unpack_from("<II", data, 0x2C)
        self.cutoff, first_minifat, n_minifat, first_difat, n_difat = struct.unpack_from("<IIIII", data, 0x38)
        difat = list(struct.unpack_from("<109I", data, 0x4C))
        s = first_difat
        for _ in range(n_difat):
            words = struct.unpack_from(f"<{self.sector // 4}I", self._sector(s))
            difat += words[:-1]
            s = words[-1]
        self.fat = []
        for s in difat[:n_fat]:
            self.fat += struct.unpack_from(f"<{self.sector // 4}I", self._sector(s))
        dirs = self._chain(first_dir)
        self.entries = {}
        root = None
        for i in range(len(dirs) // 128):
            e = dirs[i * 128:(i + 1) * 128]
            name_len, kind = struct.unpack_from("<HB", e, 64)
            start, size = struct.unpack_from("<IQ", e, 116)
            if kind == 5:
                root = (start, size)
            elif kind == 2 and name_len >= 2:
                self.entries[e[:name_len - 2].decode("utf-16-le")] = (start, size & 0xFFFFFFFF if self.sector == 512 else size)
        minifat = self._chain(first_minifat) if n_minifat else b""
        self.minifat = list(struct.unpack(f"<{len(minifat) // 4}I", minifat))
        self.ministream = self._chain(root[0])[:root[1]] if root else b""

    def _sector(self, s):
        off = (s + 1) * self.sector
        return self.data[off:off + self.sector]

    def _chain(self, start, table=None, read=None):
        table = self.fat if table is None else table
        read = read or self._sector
        out, s, seen = [], start, 0
        while s < 0xFFFFFFFA:
            out.append(read(s))
            s = table[s]
            seen += 1
            if seen > len(table) + 1:
                raise Failure("corrupt MSI: sector chain loops")
        return b"".join(out)

    def stream(self, name):
        start, size = self.entries[name]
        if size < self.cutoff:
            data = self._chain(start, self.minifat, lambda s: self.ministream[s * self.mini:(s + 1) * self.mini])
        else:
            data = self._chain(start)
        return data[:size]


def msi_stream_name(raw):
    """MSI packs table names two characters per UTF-16 unit (0x3800..0x47FF), tables start with U+4840."""
    def char(x):
        return "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._"[x]
    out = []
    for c in map(ord, raw):
        if 0x3800 <= c < 0x4800:
            c -= 0x3800
            out += [char(c & 0x3F), char((c >> 6) & 0x3F)]
        elif 0x4800 <= c < 0x4840:
            out.append(char(c - 0x4800))
        elif c == 0x4840:
            out.append("!")
        else:
            out.append(chr(c))
    return "".join(out)


def msi_properties(path):
    cfb = Cfb(Path(path).read_bytes())
    names = {msi_stream_name(k): k for k in cfb.entries}
    for needed in ("!_StringPool", "!_StringData", "!Property"):
        if needed not in names:
            raise Failure(f"MSI has no {needed[1:]} table")
    pool = cfb.stream(names["!_StringPool"])
    data = cfb.stream(names["!_StringData"])
    codepage, flags = struct.unpack_from("<HH", pool, 0)
    ref = 3 if flags & 0x8000 else 2
    encoding = {65001: "utf-8", 0: "cp1252"}.get(codepage, f"cp{codepage}")
    words = struct.unpack_from(f"<{len(pool) // 2}H", pool)
    strings, offset, i, n = {}, 0, 1, 1
    while i < len(words) // 2:
        length, refs = words[i * 2], words[i * 2 + 1]
        if length == 0 and refs == 0:
            i += 1; n += 1
            continue
        if length == 0:  # a string over 64 KB: the high word is in this entry's count, the low word follows
            length = (refs << 16) | words[i * 2 + 2]
            i += 2
        else:
            i += 1
        strings[n] = data[offset:offset + length].decode(encoding, "replace")
        offset += length
        n += 1
    table = cfb.stream(names["!Property"])
    rows = len(table) // (2 * ref)

    def cell(col, row):
        at = (col * rows + row) * ref
        return int.from_bytes(table[at:at + ref], "little")
    return {strings.get(cell(0, r), ""): strings.get(cell(1, r), "") for r in range(rows)}


def msiinfo_properties(path):
    tool = shutil.which("msiinfo")
    if not tool:
        return None
    out = subprocess.run([tool, "export", str(path), "Property"], capture_output=True, text=True, check=True).stdout
    props = {}
    for line in out.splitlines()[3:]:  # column names, types, table name
        if "\t" in line:
            k, v = line.split("\t", 1)
            props[k] = v
    return props


def check_identity(msi, version, upgrade_code=UPGRADE_CODE):
    props = msiinfo_properties(msi)
    how = "msiinfo"
    if props is None:
        props, how = msi_properties(msi), "OLE property table"
    pv, uc = props.get("ProductVersion"), props.get("UpgradeCode", "")
    if pv != version:
        raise Failure(f"{Path(msi).name}: ProductVersion {pv!r}, expected {version!r} ({how})")
    if uc.upper() != upgrade_code.upper():
        raise Failure(f"{Path(msi).name}: UpgradeCode {uc!r}, expected {upgrade_code} ({how})")
    print(f"msi ok  {Path(msi).name}: ProductVersion {pv}, UpgradeCode {uc} ({how})")
    return props


def check_validation(path, version):
    v = json.loads(Path(path).read_text(encoding="utf-8"))
    if v.get("version") != version:
        raise Failure(f"validation record is for {v.get('version')!r}, not {version}")
    if not re.fullmatch(r"[0-9a-f]{40}", v.get("source_commit", "")):
        raise Failure("validation record has no full source_commit")
    if v.get("credential_scan", {}).get("findings") != 0:
        raise Failure("credential scan not clean")
    o = v.get("publication_override")
    if o is not None and not (o.get("authorized_by_user") is True and o.get("reason")):
        raise Failure("publication override not authorized")
    print(f"validation ok  {version} from {v['source_commit']}")
    return v


def download(url, to):
    request = urllib.request.Request(url, headers={"User-Agent": "ketvoice-verify-msi"})
    with urllib.request.urlopen(request, timeout=120) as r, open(to, "wb") as f:
        shutil.copyfileobj(r, f)


def check_release(version, repo=REPO, upgrade_code=UPGRADE_CODE):
    tag = f"windows-v{version}"
    base = f"https://github.com/{repo}/releases/download/{tag}/"
    with tempfile.TemporaryDirectory() as tmp:
        msi = Path(tmp) / f"Ketvoice-{version}.msi"
        sums = Path(tmp) / "SHA256SUMS.txt"
        download(base + sums.name, sums)
        download(base + msi.name, msi)
        listed = check_local(msi, sums, version, upgrade_code)
        print(f"release ok  {tag}: {msi.name} {listed}")


def check_local(msi, sums_path, version, upgrade_code=UPGRADE_CODE):
    sums = parse_sums(Path(sums_path).read_text(encoding="utf-8"))
    name = Path(msi).name
    if name not in sums:
        raise Failure(f"{name} is not in {Path(sums_path).name}")
    actual = sha256(msi)
    if actual != sums[name]:
        raise Failure(f"{name}: SHA-256 {actual}, SHA256SUMS.txt says {sums[name]}")
    print(f"sha256 ok  {name} {actual}")
    check_identity(msi, version, upgrade_code)
    return actual


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("release"); r.add_argument("version"); r.add_argument("--repo", default=REPO)
    r.add_argument("--upgrade-code", default=UPGRADE_CODE)
    lo = sub.add_parser("local"); lo.add_argument("--msi", required=True); lo.add_argument("--sums", required=True)
    lo.add_argument("--version", required=True); lo.add_argument("--upgrade-code", default=UPGRADE_CODE)
    i = sub.add_parser("identity"); i.add_argument("msi"); i.add_argument("--version", required=True)
    i.add_argument("--upgrade-code", default=UPGRADE_CODE)
    w = sub.add_parser("sums-write"); w.add_argument("dir"); w.add_argument("files", nargs="+")
    c = sub.add_parser("sums-check"); c.add_argument("sums"); c.add_argument("dir")
    v = sub.add_parser("validation"); v.add_argument("path"); v.add_argument("version")
    a = p.parse_args(argv)
    try:
        if a.cmd == "release":
            check_release(a.version, a.repo, a.upgrade_code)
        elif a.cmd == "local":
            check_local(a.msi, a.sums, a.version, a.upgrade_code)
        elif a.cmd == "identity":
            check_identity(a.msi, a.version, a.upgrade_code)
        elif a.cmd == "sums-write":
            sys.stdout.writelines(write_sums(a.dir, a.files))
        elif a.cmd == "sums-check":
            check_sums(a.sums, a.dir)
        elif a.cmd == "validation":
            check_validation(a.path, a.version)
    except Failure as e:
        print(f"verify_msi: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
