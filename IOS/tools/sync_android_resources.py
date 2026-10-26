"""Generate native localization and PUBLIC catalog config from the Android source of truth.

Never reads a signing key, token store or environment credential. Rejects non-anon JWTs.
Run from any directory: python IOS/tools/sync_android_resources.py [--check]
"""
import argparse
import base64
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "IOS/Resources"


def quote(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t') + '"'


def expected():
    catalog = json.loads((ROOT / "app/src/main/assets/translations.json").read_text(encoding="utf-8"))
    keys = sorted(catalog["en"])
    if len(catalog) != 10 or not all(set(table) == set(keys) for table in catalog.values()):
        raise ValueError("Android must have ten translated catalogs plus French")
    tables = {"fr": {key: key for key in keys}, **catalog}
    outputs = {}
    for language, table in tables.items():
        text = "/* Generated from Android translations; edit the source catalog, not this file. */\n"
        text += "\n".join(f"{quote(key)} = {quote(table[key])};" for key in keys) + "\n"
        outputs[f"{language}.lproj/Localizable.strings"] = text
    source = (ROOT / "app/src/main/kotlin/com/ketvoice/app/data/KetvoiceApi.kt").read_text(encoding="utf-8")
    base = re.search(r'const val BASE\s*=\s*"([^"]+)"', source).group(1)
    key = re.search(r'const val ANON_KEY\s*=\s*"([^"]+)"', source).group(1)
    parts = key.split(".")
    if len(parts) != 3:
        raise ValueError("Expected the existing PUBLIC Android anon JWT")
    payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    if payload.get("role") != "anon":
        raise ValueError("Refusing to copy a privileged API key")
    if base != f"https://{payload['ref']}.supabase.co/functions/v1/ketvoice-api":
        raise ValueError("Public project identity mismatch")
    outputs["PublicCatalog.json"] = json.dumps({
        "url": base.removesuffix("/functions/v1/ketvoice-api"),
        "publishableKey": key,
        "keyRole": "anon",
    }, indent=2) + "\n"
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = expected()
    stale = []
    for relative, content in outputs.items():
        file = RESOURCES / relative
        if args.check:
            if not file.exists() or file.read_text(encoding="utf-8") != content:
                stale.append(relative)
        else:
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content, encoding="utf-8")
    if stale:
        print("iOS resources differ from Android: " + ", ".join(stale))
        return 1
    print(f"{'Checked' if args.check else 'Generated'} {len(outputs)} resources: 11 locales and anon-only catalog configuration.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
