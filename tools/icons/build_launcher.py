"""Builds the launcher icon layers of 6.0.0 Beta 4 from the matted images (tools/icons/unmatte.py).

Each variant becomes an adaptive-icon foreground (108 dp, the letter within the safe zone, transparent
around it: the launcher shows the wallpaper through the glass) at every density, plus a 256 px preview
for the icon picker in Settings, and the tinted K's silhouette becomes the monochrome (themed) layer.
usage: build_launcher.py <dir with glass.png light.png tinted.png clear.png> <res dir>
"""
import sys, os
from PIL import Image

src, res = sys.argv[1], sys.argv[2]
densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# Share of the 108 dp canvas the artwork's larger side takes: the letters fill the 66 dp safe zone, the
# glass square a little less so that its corners stay inside round masks.
fill = {"glass": .56, "light": .60, "tinted": .60, "clear": .60}

def trimmed(name):
    im = Image.open(os.path.join(src, name + ".png")).convert("RGBA")
    alpha = im.getchannel("A").point(lambda v: 255 if v > 6 else 0)
    return im.crop(alpha.getbbox())

def placed(art, size, share):
    side = max(art.size)
    scale = share * size / side
    w, h = max(1, round(art.size[0] * scale)), max(1, round(art.size[1] * scale))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(art.resize((w, h), Image.LANCZOS), ((size - w) // 2, (size - h) // 2))
    return canvas

for name, share in fill.items():
    art = trimmed(name)
    for d, px in densities.items():
        out = os.path.join(res, f"drawable-{d}", f"ic_launcher27_{name}_fg.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        placed(art, px, share).save(out, optimize=True)
    os.makedirs(os.path.join(res, "drawable-nodpi"), exist_ok=True)
    placed(art, 256, .86).save(os.path.join(res, "drawable-nodpi", f"icon_preview_{name}.png"), optimize=True)

# The themed (monochrome) layer: the K's silhouette, white on transparent.
tinted = trimmed("tinted")
for d, px in densities.items():
    layer = placed(tinted, px, fill["tinted"])
    a = layer.getchannel("A").point(lambda v: 255 if v > 90 else int(v * 255 / 90))
    mono = Image.new("RGBA", layer.size, (255, 255, 255, 0)); mono.putalpha(a)
    mono.save(os.path.join(res, f"drawable-{d}", "ic_launcher27_mono.png"), optimize=True)
print("done")
