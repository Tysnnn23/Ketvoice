# Asset pipeline — generating + preparing illustration elements

> Generate each visual element **separately**, isolated, then turn it into a transparent sprite. The structure of the piece lives *between* assets — so make many small clean ones, not one big scene.

## Prompting (Recraft v4.1 SVG for crisp vectors)

**Specificity wins.** Name a real, characterful subject; generic prompts give generic art.

| Prompt | Result |
|---|---|
| `a single delicate wildflower, soft petals…` | flat, generic, cartoonish |
| `a single elegant poppy on a tall slender curved stem, soft coral-red petals with a delicate dark center, one leaf, refined minimal flat vector, single isolated element, plain solid white background` | a graceful, recognizable poppy |

Rules:
- **Name the subject** (`poppy`, not `flower`), add **posture** (`curved stem`) and **specific features** (`dark center`).
- Always include **`no text`** — AI mangles lettering; add all words in code.
- Ask for **`single isolated element, plain solid white background`** so it strips cleanly.
- For painterly textures (watercolor washes, paper) use an **image model** (Gemini `google/gemini-2.5-flash-image`) instead of vectors.

## Strip the background → transparent sprite

Recraft draws the white background as path **#0**, `fill="rgb(255,255,255)"`. **Strip by near-white FILL, not by bounding box.** (A bbox "covers ~whole canvas" test false-positives large curved elements — it once deleted a tree's canopy.)

```python
import re
def parse(c):
    c=c.strip().lower()
    m=re.match(r'#([0-9a-f]{3})$',c)
    if m: return tuple(int(ch*2,16) for ch in m.group(1))
    m=re.match(r'#([0-9a-f]{6})$',c)
    if m: return tuple(int(c[1+2*i:3+2*i],16) for i in range(3))
    m=re.match(r'rgb\(\s*([\d.]+)[ ,]+([\d.]+)[ ,]+([\d.]+)',c)
    if m: return tuple(int(float(x)) for x in m.groups())
    return None

def strip_bg(svg_text):
    head = svg_text[:svg_text.index('>', svg_text.index('<svg'))+1]
    paths = re.findall(r'<path\b[^>]*?(?:/>|></path>)', svg_text)
    kept=[]
    for p in paths:
        m=re.search(r'fill="([^"]*)"',p); rgb=parse(m.group(1)) if m else None
        is_white = rgb and all(ch>=242 for ch in rgb)   # near-white = background
        if not is_white: kept.append(p)
    return head + "".join(kept) + "</svg>"
```

## Use the sprite in canvas

Inline each cleaned SVG as a base64 data-URI `Image`, then `drawImage` per instance (anchor at the motion pivot — base/center/corner):

```js
const img = new Image();
img.src = "data:image/svg+xml;base64," + b64;   // window.__ready() gates on img decode
// rising from a line, anchored at its base:
ctx.save(); ctx.translate(x, baseY); ctx.rotate(sway); ctx.scale(s, s);
ctx.drawImage(img, -img.naturalWidth/2, -img.naturalHeight, img.naturalWidth, img.naturalHeight);
ctx.restore();
```

## Format notes & cost
- Recraft v4.1 SVG: `<path …></path>` (not self-closing), `viewBox 0 0 2048 2048`, `transform="translate(x,y)"`, absolute coords.
- Recraft via Replicate `recraft-ai/recraft-v4.1-svg` ≈ **$0.04/image**; via OpenRouter `recraft/recraft-v4.1-vector` ≈ $0.08.
- **Often you need no asset at all** — if the concept is numbers/text/shapes (compounding, a blank page), pure code is stronger. Only generate assets when the concept wants a scene or character.
