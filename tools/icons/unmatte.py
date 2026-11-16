"""Takes the background out of a glass icon rendered on a plain background, keeping the glass see-through.

Glass on a uniform background B shows C = a*F + (1-a)*B. With B known (estimated from the image's
border by a smooth surface), each pixel's opacity a is the smallest that explains its difference from B,
and its own colour F follows: what was background becomes transparent, what is glass stays as
translucent as it looked. Used for the 6.0.0 Beta 4 launcher icons (owner's images, 2026-11-16).
usage: unmatte.py in.jpg out.png {dark|light} [floor]
"""
import sys
import numpy as np
from PIL import Image

src, dst, kind = sys.argv[1], sys.argv[2], sys.argv[3]
floor = float(sys.argv[4]) if len(sys.argv) > 4 else 0.04
img = np.asarray(Image.open(src).convert("RGB")).astype(np.float64) / 255.0
h, w, _ = img.shape
yy, xx = np.mgrid[0:h, 0:w]
u = xx / (w - 1) * 2 - 1; v = yy / (h - 1) * 2 - 1
# Background: a quadratic surface fitted to the outer band of the image, per channel, robustly.
band = (np.abs(u) > .9) | (np.abs(v) > .9)
basis = np.stack([np.ones_like(u), u, v, u * u, v * v, u * v], -1)
bg = np.zeros_like(img)
for c in range(3):
    sel = band.copy()
    for _ in range(4):
        coef, *_ = np.linalg.lstsq(basis[sel], img[..., c][sel], rcond=None)
        fit = basis @ coef
        resid = np.abs(img[..., c] - fit)
        sel = band & (resid < max(.02, np.percentile(resid[band], 80)))
    bg[..., c] = basis @ coef
bg = np.clip(bg, 0, 1)
if kind == "dark":
    # Brighter than the background: light glass, rims and highlights over black.
    a = np.max(np.clip(img - bg, 0, None) / np.maximum(1 - bg, 1e-3), axis=-1)
else:
    # Darker than the background: the glass's tint, rims and shading over white.
    a = np.max(np.clip(bg - img, 0, None) / np.maximum(bg, 1e-3), axis=-1)
a = np.clip((a - floor) / (1 - floor), 0, 1)      # the background's own grain stays out
a3 = a[..., None]
fg = np.where(a3 > 1e-3, (img - (1 - a3) * bg) / np.maximum(a3, 1e-3), 0)
fg = np.clip(fg, 0, 1)
if kind == "light":
    # White glass on white loses its body: what it darkened is kept (rims, shading), and inside the
    # letter a frosted white layer gives it back, so it reads on any wallpaper. Outside the letter
    # (a drop shadow, the background's grain) nothing is kept.
    from PIL import ImageFilter
    frost = float(sys.argv[5]) if len(sys.argv) > 5 else .42
    rim = float(sys.argv[6]) if len(sys.argv) > 6 else .18
    close = int(sys.argv[7]) if len(sys.argv) > 7 else 9
    shape = Image.fromarray(((a > rim) * 255).astype(np.uint8))
    shape = shape.filter(ImageFilter.MaxFilter(close)).filter(ImageFilter.MinFilter(close))      # close the rims
    outside = shape.copy()
    from PIL import ImageDraw
    ImageDraw.floodfill(outside, (0, 0), 128)
    inside = (np.asarray(outside) != 128).astype(np.float64)
    inside = np.asarray(Image.fromarray((inside * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))) / 255.0
    if len(sys.argv) > 8 and sys.argv[8] == "cut":
        # A drop shadow under the letter: nothing below the lowest strong rim is the letter.
        rows = np.where((a > .3).any(axis=1))[0]
        if rows.size: inside[rows.max() + 3:, :] = 0
    a = a * inside
    body = frost * inside
    total = 1 - (1 - a) * (1 - body)
    t3 = total[..., None]
    # Colour: the rims' own colour over the white body.
    fg = np.where(t3 > 1e-3, (fg * a[..., None] + np.ones_like(fg) * body[..., None] * (1 - a[..., None])) / np.maximum(t3, 1e-3), 0)
    a3 = t3
out = np.concatenate([fg, a3], -1)
Image.fromarray((out * 255 + .5).astype(np.uint8), "RGBA").save(dst)
print(dst, "opaque share", round(float((a > .5).mean()), 3), "visible share", round(float((a > .05).mean()), 3))
