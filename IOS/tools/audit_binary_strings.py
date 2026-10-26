"""Classify credential-pattern hits by Mach-O location without printing matched values."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

RULES = [rb'gh[pousr]_[A-Za-z0-9]{30,}', rb'github_pat_[A-Za-z0-9_]{40,}',
         rb'AQ[A-Za-z0-9_\-]{90,}', rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----']


def inspect(path: Path):
    data = path.read_bytes()
    if len(data) < 32 or struct.unpack_from('<I', data)[0] != 0xFEEDFACF:
        raise ValueError('Expected thin little-endian 64-bit Mach-O')
    sections = []
    strings = None
    position = 32
    commands = struct.unpack_from('<I', data, 16)[0]
    if commands > 4096: raise ValueError('Invalid load command count')
    for _ in range(commands):
        if position + 8 > len(data): raise ValueError('Truncated load command')
        command, size = struct.unpack_from('<II', data, position)
        if size < 8 or position + size > len(data): raise ValueError('Invalid load command')
        if command == 0x19:
            for index in range(struct.unpack_from('<I', data, position + 64)[0]):
                section = position + 72 + index * 80
                name = data[section:section + 16].split(b'\0')[0].decode('ascii')
                segment = data[section + 16:section + 32].split(b'\0')[0].decode('ascii')
                length = struct.unpack_from('<Q', data, section + 40)[0]
                offset = struct.unpack_from('<I', data, section + 48)[0]
                sections.append((offset, offset + length, segment + ',' + name))
        elif command == 0x2:
            if size < 24: raise ValueError('Invalid symbol table command')
            _, _, offset, length = struct.unpack_from('<IIII', data, position + 8)
            if offset < position + size or offset + length > len(data): raise ValueError('Invalid symbol strings')
            strings = (offset, offset + length)
        position += size
    hits = []
    for rule, pattern in enumerate(RULES):
        for match in re.finditer(pattern, data):
            start = data.rfind(b'\0', 0, match.start()) + 1
            in_symbols = strings is not None and strings[0] <= start <= match.start() < match.end() <= strings[1]
            swift_symbol = in_symbols and data[start:start + 3] in (b'_$s', b'_$S')
            hits.append({
                'rule': rule, 'offset': match.start(), 'length': len(match[0]),
                'sha256': hashlib.sha256(match[0]).hexdigest(),
                'section': next((name for begin, end, name in sections if begin <= match.start() < end), None),
                'in_symbol_string_table': in_symbols, 'in_native_swift_symbol': swift_symbol,
            })
    return {'file': path.name, 'matches': hits, 'matched_values_disclosed': False}


def mask_symbol_aq_matches(path: Path):
    """AQ is also a Swift mangling sequence. Mask only those hits in verified symbol-name storage.

    All GitHub/private-key detectors and JWT checks remain unchanged, as do all literal sections.
    This produces scan input only; never modify the actual executable.
    """
    report = inspect(path)
    original = bytearray(path.read_bytes())
    ignored = 0
    for hit in report['matches']:
        if hit['rule'] == 2 and hit['in_symbol_string_table']:
            position = hit['offset']
            original[position:position + 2] = b'\0\0'
            ignored += 1
    return bytes(original), ignored


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    output = json.dumps(inspect(args.binary), indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding='utf-8')
    print(output)
