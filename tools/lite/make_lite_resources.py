"""Ketvoice Lite's lighter copies of the app's images (run again whenever an image of app/src/main changes).

- Every PNG the app shows is re-encoded as *lossless* WebP in app/src/lite/res, under the same name: the
  pixels are identical, the file is smaller. The flavour's file overrides main's in the Lite APK only.
- PNGs nothing refers to any more (old launcher artwork, unused logos) are replaced in Lite by a 1x1
  transparent image; mobile and Quest keep them untouched.
- The release banner (an asset, a photograph) is a high-quality WebP for Lite: the update dialog shows it
  190 dp high, cropped.
- Photographs app/src/main/res already keeps as lossy WebP (the Vice City pictures of 6.1) get a lighter lossy
  copy in Lite, no larger than PHOTO_MAX_SIDE pixels on their longer side.

app/src/lite/lite-resources.json records the SHA-256 of each source file. LiteResourcesTest fails when a
source changed without this script being run again, or when an image replaced by a placeholder is used again.
Requires Pillow with WebP support.
"""
import hashlib
import io
import json
import re
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app'
MAIN_RES = APP / 'src/main/res'
LITE = APP / 'src/lite'
MANIFEST = LITE / 'lite-resources.json'
BANNER = APP / 'src/standard/assets/release_banner.png'
# The release banner for Lite: lossy, at its full size (the dialog crops it).
BANNER_QUALITY = 86
# Lossy WebP photographs of src/main/res, in Lite.
PHOTO_QUALITY = 72
PHOTO_MAX_SIDE = 1280


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def referenced(name):
    """Whether any source or resource outside Lite names drawable/mipmap `name`."""
    pattern = re.compile(r'(R\.(drawable|mipmap)\.%s\b|@(drawable|mipmap)/%s\b)' % (name, name))
    for path in (APP / 'src').rglob('*'):
        if path.suffix not in ('.kt', '.java', '.xml') or LITE in path.parents:
            continue
        if pattern.search(path.read_text(encoding='utf-8', errors='replace')):
            return True
    return False


def webp(image, **options):
    out = io.BytesIO()
    image.save(out, 'WEBP', **options)
    return out.getvalue()


def lossless_webp(path):
    """Whether a WebP file is lossless (a VP8L chunk) rather than a lossy photograph (a VP8 chunk)."""
    data = path.read_bytes()
    offset = 12
    while offset + 8 <= len(data):
        chunk = data[offset:offset + 4]
        size = int.from_bytes(data[offset + 4:offset + 8], 'little')
        if chunk == b'VP8 ':
            return False
        if chunk == b'VP8L':
            return True
        offset += 8 + size + (size & 1)
    return True


def main():
    record = {'converted': {}, 'placeholders': {}, 'assets': {}, 'photos': {}}
    for png in sorted(MAIN_RES.glob('*/*.png')):
        if png.name.endswith('.9.png'):
            continue  # nine-patches keep their PNG chunks
        relative = png.relative_to(MAIN_RES)
        target = LITE / 'res' / relative.parent / (png.stem + '.webp')
        target.parent.mkdir(parents=True, exist_ok=True)
        source = {'source': str(png.relative_to(APP)), 'sha256': sha256(png)}
        if referenced(png.stem):
            image = Image.open(png)
            image.load()
            data = webp(image, lossless=True, quality=100, method=6, exact=True)
            check = Image.open(io.BytesIO(data)).convert('RGBA')
            if check.tobytes() != image.convert('RGBA').tobytes():
                raise SystemExit(f'{png}: lossless WebP is not pixel-identical')
            record['converted'][str(target.relative_to(APP))] = source
        else:
            data = webp(Image.new('RGBA', (1, 1), (0, 0, 0, 0)), lossless=True)
            record['placeholders'][str(target.relative_to(APP))] = source
        target.write_bytes(data)
    for photo in sorted(MAIN_RES.glob('*/*.webp')):
        if lossless_webp(photo):
            continue
        relative = photo.relative_to(MAIN_RES)
        target = LITE / 'res' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        image = Image.open(photo)
        image.load()
        if max(image.size) > PHOTO_MAX_SIDE:
            scale = PHOTO_MAX_SIDE / max(image.size)
            image = image.resize((round(image.width * scale), round(image.height * scale)), Image.LANCZOS)
        options = {'alpha_quality': 80} if image.mode == 'RGBA' else {}
        target.write_bytes(webp(image, quality=PHOTO_QUALITY, method=6, **options))
        record['photos'][str(target.relative_to(APP))] = {'source': str(photo.relative_to(APP)), 'sha256': sha256(photo)}
    banner = LITE / 'assets/release_banner.webp'
    banner.parent.mkdir(parents=True, exist_ok=True)
    image = Image.open(BANNER).convert('RGB')
    banner.write_bytes(webp(image, quality=BANNER_QUALITY, method=6))
    record['assets'][str(banner.relative_to(APP))] = {'source': str(BANNER.relative_to(APP)), 'sha256': sha256(BANNER)}
    MANIFEST.write_text(json.dumps(record, indent=1, sort_keys=True) + '\n', encoding='utf-8')
    before = sum((APP / v['source']).stat().st_size for part in record.values() for v in part.values())
    after = sum((APP / k).stat().st_size for part in record.values() for k in part)
    print(f"{len(record['converted'])} converted, {len(record['placeholders'])} placeholders, "
          f"{len(record['photos'])} photos, banner: "
          f"{before} -> {after} bytes")


if __name__ == '__main__':
    main()
