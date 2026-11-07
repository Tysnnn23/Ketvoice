#!/usr/bin/env python3
"""Generate a stable JKS keystore for Ketvoice CI signing, with no JDK.

This machine (Termux) has no `keytool`, but OpenSSL's `libcrypto` is present.
We use it through `ctypes` to:

  1. generate an RSA-2048 key pair (`EVP_PKEY_Q_keygen`),
  2. self-sign an X.509 certificate valid until 2049 (`X509_*`),
  3. serialise both to DER,
  4. pack them into a JKS v2 keystore (`struct`, pure Python).

JKS is a plain binary container — no crypto beyond the XOR "encryption" of the
private key — so writing it by hand is safe and fully specified.

The keystore must be generated ONCE and reused forever: every release signed
with it installs over the previous one without a package conflict.

Usage:
    python3 tools/make_keystore.py OUT.jks <storepass> <alias> <keypass>
"""
import ctypes
import ctypes.util
import hashlib
import os
import struct
import sys
import time

# ---------------------------------------------------------------- libcrypto --
_lib_path = ctypes.util.find_library("crypto") or "libcrypto.so.3"
crypto = ctypes.CDLL(_lib_path)

crypto.EVP_PKEY_Q_keygen.restype = ctypes.c_void_p
crypto.EVP_PKEY_Q_keygen.argtypes = [
    ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]

crypto.X509_new.restype = ctypes.c_void_p
crypto.X509_set_version.argtypes = [ctypes.c_void_p, ctypes.c_long]
crypto.X509_getm_notBefore.restype = ctypes.c_void_p
crypto.X509_getm_notBefore.argtypes = [ctypes.c_void_p]
crypto.X509_getm_notAfter.restype = ctypes.c_void_p
crypto.X509_getm_notAfter.argtypes = [ctypes.c_void_p]
crypto.ASN1_TIME_set_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
crypto.X509_set_pubkey.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
crypto.X509_sign.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
crypto.X509_get_subject_name.restype = ctypes.c_void_p
crypto.X509_get_subject_name.argtypes = [ctypes.c_void_p]
crypto.X509_set_issuer_name.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
crypto.X509_NAME_add_entry_by_txt.argtypes = [
    ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p,
    ctypes.c_int, ctypes.c_int, ctypes.c_int]
crypto.X509_set_serialNumber.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

crypto.BIO_new_mem_buf.restype = ctypes.c_void_p
crypto.BIO_new_mem_buf.argtypes = [ctypes.c_char_p, ctypes.c_int]
crypto.BIO_free.argtypes = [ctypes.c_void_p]
crypto.BIO_ctrl_pending.restype = ctypes.c_size_t
crypto.BIO_ctrl_pending.argtypes = [ctypes.c_void_p]
crypto.BIO_read.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]

crypto.d2i_X509.restype = ctypes.c_void_p
crypto.d2i_X509.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_char_p), ctypes.c_long]
crypto.i2d_X509.restype = ctypes.c_int
crypto.i2d_X509.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte))]
crypto.X509_free.argtypes = [ctypes.c_void_p]

crypto.d2i_PUBKEY.restype = ctypes.c_void_p
# Every function returning a pointer MUST declare c_void_p: without it ctypes
# truncates the 64-bit address to a 32-bit int and the next call segfaults.
crypto.d2i_PrivateKey.restype = ctypes.c_void_p
crypto.d2i_PrivateKey.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_char_p), ctypes.c_long]
crypto.i2d_PrivateKey.restype = ctypes.c_int
crypto.i2d_PrivateKey.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte))]
crypto.X509_sign.restype = ctypes.c_int
crypto.ASN1_TIME_set_string.restype = ctypes.c_int
crypto.X509_set_pubkey.restype = ctypes.c_int
crypto.X509_set_version.restype = ctypes.c_int
crypto.X509_NAME_add_entry_by_txt.restype = ctypes.c_int
crypto.X509_set_serialNumber.restype = ctypes.c_int
crypto.X509_set_issuer_name.restype = ctypes.c_int
crypto.ASN1_INTEGER_new.restype = ctypes.c_void_p
crypto.ASN1_INTEGER_set_uint64.restype = ctypes.c_int
crypto.d2i_PUBKEY.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_char_p), ctypes.c_long]
crypto.i2d_PUBKEY.restype = ctypes.c_int
crypto.i2d_PUBKEY.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte))]
crypto.EVP_PKEY_free.argtypes = [ctypes.c_void_p]

# `OPENSSL_free` is a macro on some builds; Termux exports `CRYPTO_free` only.
__free_fn = getattr(crypto, "OPENSSL_free", None) or getattr(crypto, "CRYPTO_free", None)
if __free_fn is not None:
    __free_fn.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int] \
        if getattr(__free_fn, "__name__", "") == "CRYPTO_free" else [ctypes.c_void_p]


def openssl_free(ptr) -> None:
    if __free_fn is None:
        return
    if getattr(__free_fn, "__name__", "") == "CRYPTO_free":
        __free_fn(ptr, b"", 0)
    else:
        __free_fn(ptr)
crypto.EVP_sha256.restype = ctypes.c_void_p

ASN1_INTEGER = 0x02
MBSTRING_UTF8 = 0x1000


def _bio_pem_to_bytes(bio: int) -> bytes:
    """Drain a memory BIO into bytes."""
    size = crypto.BIO_ctrl_pending(bio)
    buf = ctypes.create_string_buffer(size)
    crypto.BIO_read(bio, buf, size)
    crypto.BIO_free(bio)
    return buf.raw


def pkcs8_to_der(pkey, out_ref) -> int:
    """i2d_PKCS8PrivateKey(pkey, &out) -> DER length, bytes malloc'd by OpenSSL."""
    fn = crypto.i2d_PKCS8PrivateKey
    fn.restype = ctypes.c_int
    fn.argtypes = [ctypes.c_void_p,
                   ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte))]
    return fn(pkey, out_ref)


def derive_key_and_cert() -> tuple[bytes, bytes]:
    """Generate an RSA-2048 key and self-sign an X.509 v3 certificate with it.

    The EVP_PKEY handle stays alive for the whole operation: serialising it and
    loading it back through d2i_PrivateKey is unnecessary and was the source of
    a segfault (i2d_PrivateKey emits PKCS#1, d2i_PrivateKey expects PKCS#8).
    Returns (private_key_der, certificate_der).
    """
    pkey = crypto.EVP_PKEY_Q_keygen(None, None, b"RSA", ctypes.c_size_t(2048))
    if not pkey:
        raise RuntimeError("EVP_PKEY_Q_keygen failed")
    cert = None
    try:
        # --- private key: PKCS#8 DER ------------------------------------
        kout = ctypes.POINTER(ctypes.c_ubyte)()
        n = pkcs8_to_der(pkey, ctypes.byref(kout))
        if n <= 0:
            raise RuntimeError("i2d_PKCS8PrivateKey failed")
        key_der = ctypes.string_at(kout, n)
        openssl_free(kout)

        # --- certificate -------------------------------------------------
        cert = crypto.X509_new()
        if not cert:
            raise RuntimeError("X509_new failed")
        crypto.X509_set_version(cert, 2)  # X.509 v3

        serial = int.from_bytes(os.urandom(16), "big") & ((1 << 127) - 1)
        crypto.X509_set_serialNumber(cert, _asn1_integer(serial))

        name = ctypes.c_void_p(crypto.X509_get_subject_name(cert))
        for field, value in ((b"CN", b"Ketvoice Release"),
                             (b"OU", b"Mobile"),
                             (b"O", b"Ketvoice"),
                             (b"C", b"FR")):
            crypto.X509_NAME_add_entry_by_txt(name, field, MBSTRING_UTF8, value, -1, -1, 0)
        crypto.X509_set_issuer_name(cert, name)
        crypto.X509_set_pubkey(cert, pkey)

        not_before = time.strftime("%Y%m%d%H%M%SZ", time.gmtime())
        crypto.ASN1_TIME_set_string(crypto.X509_getm_notBefore(cert), not_before.encode())
        crypto.ASN1_TIME_set_string(crypto.X509_getm_notAfter(cert), b"20491231235959Z")

        if crypto.X509_sign(cert, pkey, crypto.EVP_sha256()) <= 0:
            raise RuntimeError("X509_sign failed")

        cout = ctypes.POINTER(ctypes.c_ubyte)()
        if crypto.i2d_X509(cert, ctypes.byref(cout)) <= 0:
            raise RuntimeError("i2d_X509 failed")
        cert_der = ctypes.string_at(cout, crypto.i2d_X509(cert, None))
        openssl_free(cout)

        return key_der, cert_der
    finally:
        if cert:
            crypto.X509_free(cert)
        crypto.EVP_PKEY_free(pkey)


def _asn1_integer(value: int):
    """Build an ASN.1 INTEGER BIGNUM + wrapper; leaked on purpose (short process)."""
    crypto.BN_new.restype = ctypes.c_void_p
    crypto.BN_bin2bn.restype = ctypes.c_void_p
    crypto.BN_bin2bn.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p]
    crypto.ASN1_INTEGER_new.restype = ctypes.c_void_p
    crypto.ASN1_INTEGER_set_uint64.argtypes = [ctypes.c_void_p, ctypes.c_ulonglong]

    raw = value.to_bytes(16, "big").lstrip(b"\x00") or b"\x00"
    bn = crypto.BN_bin2bn(raw, len(raw), None)
    ai = crypto.ASN1_INTEGER_new()
    crypto.ASN1_INTEGER_set_uint64(ai, value & 0xFFFFFFFFFFFFFFFF)
    return ai


# --------------------------------------------------------------------- JKS --
JKS_MAGIC = 0xFEEDFEED
JKS_VERSION = 2
PRIVATE_KEY_TAG = 1


def jks_utf8(text: str) -> bytes:
    """JKS strings use 'modified UTF-8': UTF-16 code units, NUL as C0 80."""
    out = bytearray()
    for unit in struct.unpack(f">{len(text.encode('utf-16-be')) // 2}H",
                              text.encode("utf-16-be")):
        if unit == 0:
            out += b"\xc0\x80"
        elif unit < 0x80:
            out.append(unit)
        elif unit < 0x800:
            out += bytes((0xC0 | (unit >> 6), 0x80 | (unit & 0x3F)))
        else:
            out += bytes((0xE0 | (unit >> 12),
                          0x80 | ((unit >> 6) & 0x3F),
                          0x80 | (unit & 0x3F)))
    return bytes(out)


def write_utf8(out: bytearray, text: str) -> None:
    encoded = jks_utf8(text)
    out += struct.pack(">H", len(encoded))
    out += encoded


def xor_stream(data: bytes, password: str) -> bytes:
    """JKS key obfuscation: SHA1(pass || chain) re-seeded every 20 bytes."""
    pass_bytes = password.encode("utf-16-be")
    stream = bytearray()
    prev = b""
    while len(stream) < len(data):
        prev = hashlib.sha1(pass_bytes + prev).digest()
        stream += prev
    return bytes(a ^ b for a, b in zip(data, stream))


def build_jks(key_der: bytes, cert_der: bytes, alias: str,
              storepass: str, keypass: str) -> bytes:
    entry = bytearray()
    entry += struct.pack(">Q", int(time.time() * 1000))
    write_utf8(entry, alias)
    write_utf8(entry, "X.509")   # key algorithm / key type
    enc = xor_stream(key_der, keypass)
    entry += struct.pack(">I", len(enc)) + enc
    entry += struct.pack(">I", 1)                 # one certificate
    write_utf8(entry, "X.509")
    entry += struct.pack(">I", len(cert_der)) + cert_der

    out = bytearray()
    out += struct.pack(">I", JKS_MAGIC)
    out += struct.pack(">I", JKS_VERSION)
    out += struct.pack(">I", 1)                   # one entry
    out += struct.pack(">I", PRIVATE_KEY_TAG)
    out += struct.pack(">I", len(entry))
    out += entry

    # The keystore digest covers the WHOLE file from the magic number, with the
    # passphrase prepended as UTF-16BE and the random seed inserted before it.
    seed = os.urandom(20)
    digest = hashlib.sha1(
        storepass.encode("utf-16-be") + seed + bytes(out)).digest()
    out += seed
    out += digest
    return bytes(out)


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__)
        return 2
    path, storepass, alias, keypass = sys.argv[1:5]

    print("generating RSA-2048 key + self-signed certificate ...", flush=True)
    key_der, cert_der = derive_key_and_cert()
    print(f"private key: {len(key_der)} bytes (PKCS#8 DER)")
    print(f"certificate: {len(cert_der)} bytes (DER)")

    blob = build_jks(key_der, cert_der, alias, storepass, keypass)
    with open(path, "wb") as fh:
        fh.write(blob)
    print(f"wrote {path} ({len(blob)} bytes), alias={alias}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
