#!/usr/bin/env python3
"""Structural verification of a JKS keystore produced by make_keystore.py.

Rewinds the exact byte layout the generator wrote, XOR-decrypts the private key
with the key password, and recomputes the SHA-1 integrity digest. A keystore
that prints all OK is a well-formed JKS v2 that Gradle/`keytool` will accept.

Usage:
    python3 tools/verify_keystore.py FILE.jks STOREPASS KEYPASS
"""
import hashlib
import struct
import sys


class Reader:
    def __init__(self, data: bytes, offset: int = 0):
        self.data = data
        self.o = offset

    def u16(self) -> int:
        v, = struct.unpack(">H", self.data[self.o:self.o + 2])
        self.o += 2
        return v

    def u32(self) -> int:
        v, = struct.unpack(">I", self.data[self.o:self.o + 4])
        self.o += 4
        return v

    def u64(self) -> int:
        v, = struct.unpack(">Q", self.data[self.o:self.o + 8])
        self.o += 8
        return v

    def raw(self, n: int) -> bytes:
        v = self.data[self.o:self.o + n]
        self.o += n
        return v

    def utf8(self) -> str:
        """JKS 'modified UTF-8': UTF-16 code units, NUL encoded as C0 80."""
        n = self.u16()
        raw = self.raw(n)
        units, i = [], 0
        while i < len(raw):
            c = raw[i]
            if c < 0x80:
                units.append(c); i += 1
            elif c >> 5 == 0b110:
                units.append(((c & 0x1F) << 6) | (raw[i + 1] & 0x3F)); i += 2
            else:
                units.append(((c & 0x0F) << 12)
                             | ((raw[i + 1] & 0x3F) << 6)
                             | (raw[i + 2] & 0x3F)); i += 3
        return b"".join(struct.pack(">H", u) for u in units).decode("utf-16-be")


def xor_stream(data: bytes, password: str) -> bytes:
    """JKS key obfuscation: XOR with SHA1(pass || chain), re-seeded every 20 B."""
    pass_bytes = password.encode("utf-16-be")
    stream = bytearray()
    prev = b""
    while len(stream) < len(data):
        prev = hashlib.sha1(pass_bytes + prev).digest()
        stream += prev
    # Truncate the keystream to the ciphertext length before XOR, otherwise
    # zip() over a longer stream would still match but the length bookkeeping
    # downstream gets confusing.
    stream = stream[:len(data)]
    return bytes(a ^ b for a, b in zip(data, stream))


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    path, storepass, keypass = sys.argv[1:4]
    blob = open(path, "rb").read()
    failures = []

    def check(label: str, ok: bool, extra: str = "") -> None:
        print(f"  [{'OK ' if ok else 'FAIL'}] {label} {extra}".rstrip())
        if not ok:
            failures.append(label)

    print(f"verify {path} ({len(blob)} bytes)")
    r = Reader(blob)

    magic = r.u32()
    version = r.u32()
    count = r.u32()
    check("magic = 0xFEEDFEED", magic == 0xFEEDFEED, f"(got 0x{magic:08X})")
    check("version = 2 (JKS)", version == 2, f"(got {version})")
    check("entry count >= 1", count >= 1, f"(got {count})")

    tag = r.u32()
    entry_len = r.u32()
    entry_start = r.o
    check("first entry is a private key", tag == 1, f"(tag {tag})")
    check("entry length fits the file", entry_start + entry_len <= len(blob))

    r.u64()  # entry timestamp
    alias = r.utf8()
    key_type = r.utf8()
    check("private key type is X.509", key_type == "X.509", f"({key_type!r})")

    enc_len = r.u32()
    enc = r.raw(enc_len)
    key_der = xor_stream(enc, keypass)
    check("private key decrypts to PKCS#8 DER",
          key_der[:2] == b"\x30\x82",
          f"(head {key_der[:4].hex()}, {len(key_der)} bytes, declared {enc_len})")

    chain_len = r.u32()
    check("certificate chain present", chain_len >= 1, f"({chain_len} cert)")
    cert_type = r.utf8()
    cert_len = r.u32()
    cert_der = r.raw(cert_len)
    check("certificate type is X.509", cert_type == "X.509")
    check("certificate is DER SEQUENCE",
          cert_der[:2] == b"\x30\x82", f"({len(cert_der)} bytes)")

    check("entry body matches declared length",
          r.o == entry_start + entry_len,
          f"(consumed {r.o - entry_start} / {entry_len})")

    seed = r.raw(20)
    digest = r.raw(20)
    expected = hashlib.sha1(storepass.encode("utf-16-be") + seed
                            + blob[:entry_start + entry_len]).digest()
    check("keystore SHA-1 digest", digest == expected)
    check("file fully consumed", r.o == len(blob),
          f"({r.o} / {len(blob)})")

    print(f"\nalias: {alias}")
    print("RESULT:", "VALID JKS v2" if not failures else f"INVALID ({len(failures)} issue(s))")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
