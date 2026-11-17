#!/usr/bin/env python3
"""Fails when a Ketvoice method in an APK needs 256 registers or more (6.1.2).

6.1.0 shipped `Automix.transition` with 287 registers: Android's verifier rejected the whole Automix class on the
owner's phone (API 36) and the app closed at launch, while every JVM/Robolectric test passed, because the JVM never
runs the ART verifier. This reads every method's register count with `dexdump` and stops the build before such a
method ships. Usage: check_dex_registers.py <build-tools dir> <apk>...
"""
import re, subprocess, sys, tempfile, zipfile
from pathlib import Path

LIMIT = 256
PREFIX = 'Lcom/ketvoice/'

def heavy(dexdump, dex):
    out = subprocess.run([dexdump, str(dex)], capture_output=True, check=True).stdout.decode('utf-8', 'replace')
    found, cls, name = [], None, None
    for line in out.splitlines():
        m = re.match(r"\s+Class descriptor\s+:\s+'([^']+)'", line)
        if m: cls = m.group(1); continue
        m = re.match(r"\s+name\s+:\s+'([^']+)'", line)
        if m: name = m.group(1); continue
        m = re.match(r"\s+registers\s+:\s+(\d+)", line)
        if m and cls and cls.startswith(PREFIX) and int(m.group(1)) >= LIMIT:
            found.append((cls, name, int(m.group(1))))
    return found

def main():
    build_tools, apks = Path(sys.argv[1]), sys.argv[2:]
    dexdump = build_tools / 'dexdump'
    bad = []
    for apk in apks:
        with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
            for n in z.namelist():
                if re.fullmatch(r'classes\d*\.dex', n):
                    z.extract(n, tmp)
                    bad += [(apk, *h) for h in heavy(dexdump, Path(tmp) / n)]
    for apk, cls, name, regs in bad:
        print(f'::error::{apk}: {cls}->{name} uses {regs} registers (limit {LIMIT - 1}); split the method')
    if bad: sys.exit(1)
    print('register check ok:', ', '.join(apks))

if __name__ == '__main__':
    main()
