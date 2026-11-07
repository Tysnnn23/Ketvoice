#!/usr/bin/env python3
"""Upload the Ketvoice signing secrets to GitHub Actions, libsodium-sealed.

GitHub stores Actions secrets encrypted with a per-repository X25519 public key
(libsodium sealed box). This script:

  1. fetches the repo public key from the REST API,
  2. seals each value with PyNaCl's `SealedBox`,
  3. PUTs the base64 ciphertext back to the secrets endpoint.

Secrets written:
  KETVOICE_KEYSTORE_B64    base64 of release.keystore
  KETVOICE_STORE_PASSWORD  keystore password
  KETVOICE_KEY_PASSWORD    key password (same value here)
  KETVOICE_KEY_ALIAS       'ketvoice'

Usage:
    python3 tools/push_secrets.py <owner/repo> <token> <creds_file> <keystore>
"""
import base64
import json
import sys
import urllib.request

from nacl import encoding, public

API = "https://api.github.com"


def request(url: str, token: str, method: str = "GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"token {token}")
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else {}


def encrypt(public_key_b64: str, value: str) -> str:
    pk = public.PublicKey(public_key_b64.encode(), encoding.Base64Encoder())
    sealed = public.SealedBox(pk).encrypt(value.encode())
    return base64.b64encode(sealed).decode()


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__)
        return 2
    repo, token, creds_file, keystore_path = sys.argv[1:5]

    creds = {}
    for line in open(creds_file):
        line = line.strip()
        if line and "=" in line:
            k, v = line.split("=", 1)
            creds[k] = v

    keystore_b64 = base64.b64encode(open(keystore_path, "rb").read()).decode()

    payload = {
        "KETVOICE_KEYSTORE_B64": keystore_b64,
        "KETVOICE_STORE_PASSWORD": creds["STOREPASS"],
        "KETVOICE_KEY_PASSWORD": creds["KEYPASS"],
        "KETVOICE_KEY_ALIAS": "ketvoice",
    }

    print(f"repo: {repo}")
    key = request(f"{API}/repos/{repo}/actions/secrets/public-key", token)
    key_id = key["key_id"]
    print(f"repo public key: {key_id}")

    for name, value in payload.items():
        sealed = encrypt(key["public_key"], value)
        request(f"{API}/repos/{repo}/actions/secrets/{name}", token,
                method="PUT", body={"encrypted_value": sealed, "key_id": key_id})
        preview = f"{value[:8]}...({len(value)} chars)" if len(value) > 20 else value
        print(f"  set {name:26} {preview}")

    print("\nall secrets uploaded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
