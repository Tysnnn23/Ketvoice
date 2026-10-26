"""Scan the original resources and a scan-only Mach-O copy with symbol-name AQ false positives classified."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

from audit_binary_strings import mask_symbol_aq_matches


def scan(app: Path):
    binary = app / 'Ketvoice'
    data, classified = mask_symbol_aq_matches(binary)
    files = [file for file in app.rglob('*') if file.is_file() and file != binary]
    print(f'Classified {classified} AQ-pattern hits in the verified Mach-O symbol-name table. '
          'Literal sections, GitHub token patterns, private keys and JWT checks remain scanned. '
          'Original executable unchanged.', flush=True)
    scanner = Path(__file__).resolve().parents[2] / 'tools/scan_release.py'
    with tempfile.TemporaryDirectory(prefix='ketvoice-scan-') as directory:
        scan_copy = Path(directory) / 'Ketvoice-symbol-aware-scan-input'
        scan_copy.write_bytes(data)
        return subprocess.run([sys.executable, str(scanner), str(scan_copy), *map(str, files)], check=False).returncode


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', type=Path)
    args = parser.parse_args()
    sys.exit(scan(args.app))
