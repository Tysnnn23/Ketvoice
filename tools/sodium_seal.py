"""Pure-Python libsodium `crypto_box_seal` (sealed box), no PyNaCl needed.

GitHub encrypts Actions secrets with a libsodium sealed box:
    sealed = epk(32) || XSalsa20-Poly1305(k, n, plaintext)
    k       = HSalsa20(X25519(sk_ephemeral, pk_recipient), zero-nonce)
    n       = BLAKE2b(epk || pk_recipient, 24)

This module supplies the pieces OpenSSL does not expose on Termux (HSalsa20,
Salsa20 core, Poly1305) and borrows X25519 from libcrypto through ctypes.
Verified against published libsodium test vectors in tools/selftest_seal.py.
"""
import ctypes
import ctypes.util
import hashlib
import struct

# --------------------------------------------------------------------------
# X25519 via libcrypto
# --------------------------------------------------------------------------
_lib = ctypes.CDLL(ctypes.util.find_library("crypto") or "libcrypto.so.3")


def _decl(name, restype, argtypes):
    fn = getattr(_lib, name)
    fn.restype = restype
    fn.argtypes = argtypes
    return fn


_new_raw_public = _decl("EVP_PKEY_new_raw_public_key",
                        ctypes.c_void_p,
                        [ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t])
_new_raw_private = _decl("EVP_PKEY_new_raw_private_key",
                         ctypes.c_void_p,
                         [ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t])
_get_raw_public = _decl("EVP_PKEY_get_raw_public_key",
                        ctypes.c_int,
                        [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t)])
_ctx_new = _decl("EVP_PKEY_CTX_new", ctypes.c_void_p, [ctypes.c_void_p, ctypes.c_void_p])
_ctx_free = _decl("EVP_PKEY_CTX_free", None, [ctypes.c_void_p])
_pkey_free = _decl("EVP_PKEY_free", None, [ctypes.c_void_p])
_derive_init = _decl("EVP_PKEY_derive_init", ctypes.c_int, [ctypes.c_void_p])
_derive_set_peer = _decl("EVP_PKEY_derive_set_peer", ctypes.c_int, [ctypes.c_void_p, ctypes.c_void_p])
_derive = _decl("EVP_PKEY_derive", ctypes.c_int,
                [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t)])

EVP_PKEY_X25519 = 1034


def x25519_public_from_private(sk: bytes) -> bytes:
    pkey = _new_raw_private(EVP_PKEY_X25519, None, sk, len(sk))
    if not pkey:
        raise RuntimeError("EVP_PKEY_new_raw_private_key failed")
    try:
        out = ctypes.create_string_buffer(32)
        n = ctypes.c_size_t(32)
        if _get_raw_public(pkey, out, ctypes.byref(n)) != 1:
            raise RuntimeError("EVP_PKEY_get_raw_public_key failed")
        return out.raw[:n.value]
    finally:
        _pkey_free(pkey)


def x25519(sk: bytes, pk: bytes) -> bytes:
    """X25519 scalar multiplication (sk is the private scalar, pk the peer point)."""
    priv = _new_raw_private(EVP_PKEY_X25519, None, sk, len(sk))
    if not priv:
        raise RuntimeError("EVP_PKEY_new_raw_private_key failed")
    pub = _new_raw_public(EVP_PKEY_X25519, None, pk, len(pk))
    if not pub:
        _pkey_free(priv)
        raise RuntimeError("EVP_PKEY_new_raw_public_key failed")
    ctx = _ctx_new(priv, None)
    try:
        if not ctx or _derive_init(ctx) != 1:
            raise RuntimeError("EVP_PKEY_derive_init failed")
        if _derive_set_peer(ctx, pub) != 1:
            raise RuntimeError("EVP_PKEY_derive_set_peer failed")
        out = ctypes.create_string_buffer(32)
        n = ctypes.c_size_t(32)
        if _derive(ctx, out, ctypes.byref(n)) != 1:
            raise RuntimeError("EVP_PKEY_derive failed")
        return out.raw[:n.value]
    finally:
        _ctx_free(ctx)
        _pkey_free(pub)
        _pkey_free(priv)


# --------------------------------------------------------------------------
# Salsa20 core
# --------------------------------------------------------------------------
_SIGMA = struct.unpack("<4I", b"expand 32-byte k")


def _rotl(v: int, c: int) -> int:
    v &= 0xFFFFFFFF
    return ((v << c) | (v >> (32 - c))) & 0xFFFFFFFF


def _quarter(x: list, a: int, b: int, c: int, d: int) -> None:
    x[b] ^= _rotl((x[a] + x[d]) & 0xFFFFFFFF, 7)
    x[c] ^= _rotl((x[b] + x[a]) & 0xFFFFFFFF, 9)
    x[d] ^= _rotl((x[c] + x[b]) & 0xFFFFFFFF, 13)
    x[a] ^= _rotl((x[d] + x[c]) & 0xFFFFFFFF, 18)


def salsa20_block(key: bytes, nonce: bytes, counter: int) -> bytes:
    """One 64-byte Salsa20 keystream block (20 rounds)."""
    k = struct.unpack("<8I", key)
    n = struct.unpack("<2I", nonce)
    state = [
        _SIGMA[0], k[0], k[1], k[2], k[3], _SIGMA[1], n[0], n[1],
        counter & 0xFFFFFFFF, (counter >> 32) & 0xFFFFFFFF, _SIGMA[2], k[4], k[5], k[6], k[7], _SIGMA[3],
    ]
    x = state[:]
    for _ in range(10):
        _quarter(x, 0, 4, 8, 12)
        _quarter(x, 5, 9, 13, 1)
        _quarter(x, 10, 14, 2, 6)
        _quarter(x, 15, 3, 7, 11)
        _quarter(x, 0, 1, 2, 3)
        _quarter(x, 5, 6, 7, 4)
        _quarter(x, 10, 11, 8, 9)
        _quarter(x, 15, 12, 13, 14)
    return struct.pack("<16I", *[((x[i] + state[i]) & 0xFFFFFFFF) for i in range(16)])


def hsalsa20(key: bytes, nonce: bytes) -> bytes:
    """HSalsa20: the 32-byte subkey used to build XSalsa20 from Salsa20."""
    k = struct.unpack("<8I", key)
    n = struct.unpack("<4I", nonce)
    state = [
        _SIGMA[0], k[0], k[1], k[2], k[3], _SIGMA[1], n[0], n[1],
        n[2], n[3], _SIGMA[2], k[4], k[5], k[6], k[7], _SIGMA[3],
    ]
    x = state[:]
    for _ in range(10):
        _quarter(x, 0, 4, 8, 12)
        _quarter(x, 5, 9, 13, 1)
        _quarter(x, 10, 14, 2, 6)
        _quarter(x, 15, 3, 7, 11)
        _quarter(x, 0, 1, 2, 3)
        _quarter(x, 5, 6, 7, 4)
        _quarter(x, 10, 11, 8, 9)
        _quarter(x, 15, 12, 13, 14)
    # HSalsa20 outputs words 0,5,10,15,6,7,8,9 with no feed-forward.
    return struct.pack("<8I", x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9])


def xsalsa20_stream(key: bytes, nonce24: bytes, length: int) -> bytes:
    """XSalsa20 keystream: HSalsa20(key, nonce[0:16]) then Salsa20 with nonce[16:24]."""
    subkey = hsalsa20(key, nonce24[:16])
    out = bytearray()
    counter = 0
    while len(out) < length:
        out += salsa20_block(subkey, nonce24[16:24], counter)
        counter += 1
    return bytes(out[:length])


# --------------------------------------------------------------------------
# Poly1305
# --------------------------------------------------------------------------
_P1305 = (1 << 130) - 5


def poly1305(key: bytes, msg: bytes) -> bytes:
    r = int.from_bytes(key[:16], "little") & 0x0FFFFFFC0FFFFFFC0FFFFFFC0FFFFFFF
    s = int.from_bytes(key[16:32], "little")
    acc = 0
    for i in range(0, len(msg), 16):
        chunk = msg[i:i + 16]
        n = int.from_bytes(chunk + b"\x01", "little")
        acc = ((acc + n) * r) % _P1305
    return ((acc + s) & 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF).to_bytes(16, "little")


# --------------------------------------------------------------------------
# Sealed box
# --------------------------------------------------------------------------
def _blake2b24(*parts: bytes) -> bytes:
    h = hashlib.blake2b(digest_size=24)
    for p in parts:
        h.update(p)
    return h.digest()


def seal(recipient_pk: bytes, plaintext: bytes, ephemeral_sk: bytes | None = None) -> bytes:
    """crypto_box_seal: anonymous public-key encryption.

    Layout: epk(32) || MAC(16) || ciphertext. `crypto_box_easy` writes the
    Poly1305 tag in front of the ciphertext (it seals into a detached buffer at
    c + MACBYTES and puts the MAC at c), so the tag must come before the body.
    """
    import os
    esk = ephemeral_sk or os.urandom(32)
    epk = x25519_public_from_private(esk)
    shared = x25519(esk, recipient_pk)
    key = hsalsa20(shared, b"\x00" * 16)          # crypto_box_beforenm
    nonce = _blake2b24(epk, recipient_pk)
    # crypto_secretbox = XSalsa20 keystream; first 32 bytes are the Poly1305 key.
    stream = xsalsa20_stream(key, nonce, 32 + len(plaintext))
    poly_key, cipher_xor = stream[:32], stream[32:]
    cipher = bytes(a ^ b for a, b in zip(plaintext, cipher_xor))
    tag = poly1305(poly_key, cipher)
    return epk + tag + cipher
