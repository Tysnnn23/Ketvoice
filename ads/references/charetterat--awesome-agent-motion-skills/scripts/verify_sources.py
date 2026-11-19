#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify every source cited in data/*.json is still live and still says what we claim.

Each entry names a URL plus an `expected_token` that must appear in the fetched body.
A citation that stops containing its token is a claim that went stale, and this exits non-zero.

Runs through the 19001 HK proxy. Used as a CI gate, so a stale guide cannot merge.
"""
import glob
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

PROXY = os.environ.get("CLOAK_PROXY", "http://127.0.0.1:19001")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}))


def check(op, url, expected_token, attempts=3):
    """Fetch a URL; return (http_status, verdict).

    ⛔ STALE is not the same as unreachable: a 200 that no longer contains the
    token is a *live page saying something else*, which is the failure that
    actually matters here.

    ⛔ A transport error is also not STALE, and must never be reported as if it
    were. A gate that fails at random on a flaky fetch is a gate people learn to
    ignore, so transport errors are retried and only reported as UNREACHABLE
    after every attempt fails. The three verdicts stay distinct on purpose:
    OK / STALE (live, claims changed) / UNREACHABLE (could not read).
    """
    last = (0, "ERR: not attempted")
    for i in range(attempts):
        req = urllib.request.Request(url, headers={"User-Agent": UA}, method="GET")
        try:
            with op.open(req, timeout=40) as r:
                text = r.read().decode("utf-8", "replace")
                clean = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text))
                if expected_token.lower() in clean.lower():
                    return r.status, "OK"
                # ⛔ A live page that says something else is STALE on the first
                # success — retrying cannot change the page's contents.
                return r.status, "STALE"
        except urllib.error.HTTPError as e:
            # 404/403 are answers, not transport noise. Do not retry.
            return e.code, "HTTP_ERROR"
        except Exception as e:
            last = (0, f"ERR: {e}")
            if i < attempts - 1:
                time.sleep(2 * (i + 1))
    return last[0], f"UNREACHABLE ({last[1]})"


def collect():
    """Pull verification_sources out of every data file, tagged by filename."""
    out = []
    for path in sorted(glob.glob(os.path.join(ROOT, "data", "*.json"))):
        d = json.load(open(path, encoding="utf-8"))
        for s in d.get("verification_sources", []):
            s = dict(s)
            s["_from"] = os.path.basename(path)
            out.append(s)
    return out


def main():
    op = opener()
    sources = collect()
    if not sources:
        # ⛔ An empty list is a pass that proves nothing — fail instead.
        sys.exit("No verification_sources found. The gate must be able to fail.")

    failed = []
    for s in sources:
        status, res = check(op, s["url"], s["expected_token"])
        marker = "✅" if res == "OK" else "🔴"
        print(f"{marker} [{res:<11}] {status:<3} | {s['_from']:<18} | {s['url']}")
        if res != "OK":
            failed.append(f"{s['url']}  ({res}, {status})")

    if failed:
        print(f"\n🔴 {len(failed)}/{len(sources)} sources failed:")
        for f in failed:
            print(f"   - {f}")
        sys.exit(1)
    print(f"\nAll {len(sources)} sources verified.")


if __name__ == "__main__":
    main()
