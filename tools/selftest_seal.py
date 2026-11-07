#!/usr/bin/env python3
"""Self-test sodium_seal.py against published primitive test vectors."""
import sys

sys.path.insert(0, "tools")
import sodium_seal as S


def hx(s: str) -> bytes:
    return bytes.fromhex(s.replace(" ", "").replace("\n", ""))


fails = 0


def check(name, got, want):
    global fails
    ok = got == want
    if not ok:
        fails += 1
    print(f"  [{'OK ' if ok else 'FAIL'}] {name}")
    if not ok:
        print(f"        got  {got.hex()}")
        print(f"        want {want.hex()}")


# Salsa20 core, ECRYPT vector: key 0x80,0.., nonce 0, counter 0 -> known stream
key = bytes(range(32))
nonce0 = b"\x00" * 8
blk = S.salsa20_block(key + b"", nonce0, 0) if False else S.salsa20_block(bytes(range(32)), b"\x00" * 8, 0)
# ECRYPT Salsa20/20 with all-zero key and nonce, block 0
zero_block = S.salsa20_block(b"\x00" * 32, b"\x00" * 8, 0)
check("Salsa20/20 zero key, zero nonce, block 0",
      zero_block,
      hx("9a97f65b9b4c721b960a672145fca8d4e32e67f9111ea979ce9c4826806aeee6"
         "3de9c0da2bd7f91ebcb2639bf989c6251b29bf38d39a9bdce7c55f4b2ac12a39"))

# XSalsa20 keystream — the authoritative NaCl/libsodium vector. It exercises
# HSalsa20, the Salsa20 rounds and the counter wiring all at once, so it is a
# far stronger check than a hand-copied HSalsa20 digest.
xs_key = hx("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389")
xs_nonce = hx("69696ee955b62b73cd62bda875fc73d68219e0036b7a0b37")
check("XSalsa20 keystream (NaCl vector, first block)",
      S.xsalsa20_stream(xs_key, xs_nonce, 32),
      hx("eea6a7251c1e72916d11c2cb214d3c252539121d8e234e652d651fa4c8cff880"))

# Poly1305 (RFC 8439 section 2.5.2)
poly_key = hx("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
msg = b"Cryptographic Forum Research Group"
check("Poly1305 RFC 8439 2.5.2",
      S.poly1305(poly_key, msg),
      hx("a8061dc1305136c6c22b8baf0c0127a9"))

# X25519 (RFC 7748 section 6.1, Alice's key pair)
a_sk = hx("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
a_pk = S.x25519_public_from_private(a_sk)
check("X25519 public from private",
      a_pk,
      hx("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"))

b_sk = hx("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
b_pk = S.x25519_public_from_private(b_sk)
check("X25519 shared secret (Alice x Bob)",
      S.x25519(a_sk, b_pk),
      hx("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"))

# Sealed box round-trip using libsodium's documented construction
rec_sk = bytes(range(32))
rec_pk = S.x25519_public_from_private(rec_sk)
eph_sk = bytes(range(32, 64))
sealed = S.seal(rec_pk, b"hello ketvoice", ephemeral_sk=eph_sk)
check("seal length = 32 + len + 16", bytes([len(sealed)]), bytes([32 + 14 + 16]))

# Decrypt by hand with the same primitives (mirror of libsodium's open)
epk = sealed[:32]
tag, cipher = sealed[32:48], sealed[48:]
shared = S.x25519(rec_sk, epk)
key = S.hsalsa20(shared, b"\x00" * 16)
nonce = S._blake2b24(epk, rec_pk)
stream = S.xsalsa20_stream(key, nonce, 32 + len(cipher))
poly_key, cipher_xor = stream[:32], stream[32:]
check("sealed box MAC verifies", S.poly1305(poly_key, cipher), tag)
check("sealed box round-trip",
      bytes(a ^ b for a, b in zip(cipher, cipher_xor)), b"hello ketvoice")

print(f"\n{'ALL VECTORS PASS' if not fails else f'{fails} FAILURE(S)'}")
raise SystemExit(1 if fails else 0)
