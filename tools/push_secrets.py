#!/usr/bin/env python3
"""Upload the Ketvoice signing secrets to GitHub Actions, libsodium-sealed.

GitHub encrypts Actions secrets with a libsodium sealed box against a
per-repository X25519 public key. This script fetches that key, seals each
value with the pure-Python implementation in tools/sodium_seal.py (no PyNaCl
required — Termux has no wheel), then PUTs the base64 ciphertext.

Secrets written:
  KETVOICE_KEYSTORE_B64    base64 of release.keystore
  KETVOICE_STORE_PASSWORD  keystore password
  KETVOICE_KEY_PASSWORD    key password
  KETVOICE_KEY_ALIAS       'ketvoice'

Usage:
    GITHUB_TOKEN=... python3 tools/push_secrets.py <owner/repo>
"""
import base64
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sodium_seal

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


def main() -> int:
    repo = sys.argv[1] if len(sys.argv) > 1 else "Tysnnn23/Ketvoice"
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print("GITHUB_TOKEN is not set", file=sys.stderr)
        return 2

    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    keystore = os.path.join(here, "release.keystore")
    creds_file = os.path.join(here, ".keystore-creds")

    creds = {}
    with open(creds_file) as fh:
        for line in fh:
            line = line.strip()
            if line and "=" in line:
                k, v = line.split("=", 1)
                creds[k] = v

    payload = {
        "KETVOICE_KEYSTORE_B64": base64.b64encode(open(keystore, "rb").read()).decode(),
        "KETVOICE_STORE_PASSWORD": creds["STOREPASS"],
        "KETVOICE_KEY_PASSWORD": creds["KEYPASS"],
        "KETVOICE_KEY_ALIAS": "ketvoice",
    }

    key = request(f"{API}/repos/{repo}/actions/secrets/public-key", token)
    # The repo-level endpoint returns {"key_id", "key"}; the older/org form
    # used "public_key". Accept either so the script survives API drift.
    key_id = key["key_id"]
    public_key = base64.b64decode(key.get("key") or key.get("public_key"))
    print(f"repo {repo}  key_id {key_id}  x25519 key {len(public_key)} bytes")

    for name, value in payload.items():
        sealed = sodium_seal.seal(public_key, value.encode())
        request(f"{API}/repos/{repo}/actions/secrets/{name}", token,
                method="PUT",
                body={"encrypted_value": base64.b64encode(sealed).decode(),
                      "key_id": key_id})
        shown = value if name.endswith("ALIAS") else f"<{len(value)} bytes>"
        print(f"  set {name:24} {shown}")

    print("\nall secrets uploaded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
