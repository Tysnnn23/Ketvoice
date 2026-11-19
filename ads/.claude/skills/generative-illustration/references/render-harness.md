# Render harness — deterministic frames + music

> Every value is a pure function of time `t`. Playwright screenshots each frame; ffmpeg assembles. No `requestAnimationFrame`, no CSS transitions (they desync from the grabber).

## Toolchain (local, no API key)
- Node + **`playwright-core`** (auto-fetches Chromium; or `npx playwright install chromium`)
- **`ffmpeg`**

## The HTML contract

```html
<canvas id="c" width="1080" height="1350"></canvas>
<script>
const ctx = document.getElementById('c').getContext('2d');
function render(t){ /* draw EVERYTHING as a pure function of t */ }
window.__T = 12.5;                              // total seconds
window.__seek = (t) => render(+t||0);           // grabber calls this per frame
window.__ready = () => true;                    // or: () => imagesLoaded>=N
window.__seek(new URLSearchParams(location.search).get('t') || 0);  // ?t= renders one frame
</script>
```

- Stage an exact pixel size (e.g. 1080×1350 vertical for RED, 1080×1080 square for X).
- Stateful sims (particles): step forward deterministically with a **seeded RNG** (e.g. mulberry32) so every render is identical.
- Use a seeded grain overlay for the soft/“Softcore” texture.

## Capture (one browser, frame by frame)

```js
// capture.cjs  — NODE_PATH must point at playwright-core
const path=require('path'); const {chromium}=require('playwright-core');
(async()=>{
  const fs=require('fs'); fs.rmSync('frames',{recursive:true,force:true}); fs.mkdirSync('frames');
  const b=await chromium.launch();
  const p=await b.newPage({viewport:{width:1080,height:1350},deviceScaleFactor:1});
  await p.goto('file://'+path.resolve('anim.html')+'?t=0');
  await p.waitForFunction(()=>window.__ready&&window.__ready(),{timeout:9000}).catch(()=>0);
  const T=await p.evaluate(()=>window.__T); const FPS=30; const tot=Math.ceil(T*FPS);
  for(let f=0;f<tot;f++){ await p.evaluate(t=>window.__seek(t), f/FPS);
    await p.screenshot({path:'frames/f'+String(f).padStart(4,'0')+'.png'}); }
  await b.close();
})();
```

```bash
# even dimensions + yuv420p (odd sizes fail libx264)
ffmpeg -y -framerate 30 -i frames/f%04d.png -c:v libx264 -pix_fmt yuv420p -crf 18 -movflags +faststart out.mp4
```

**QC:** sample a contact sheet across the WHOLE duration (not just the first seconds), and read it yourself — blank frames? overlaps? does the concept read? Fix and re-render.

## Music (Lyria 3 via OpenRouter)

AI-generated → royalty-free. **Audio output requires `stream: true`**; chunks arrive base64 in `delta.audio.data`:

```bash
curl -s -N https://openrouter.ai/api/v1/chat/completions \
 -H "Authorization: Bearer $OPENROUTER_API_KEY" -H "Content-Type: application/json" \
 -d '{"model":"google/lyria-3-clip-preview","messages":[{"role":"user","content":"soft slow emotional solo piano, warm, gentle forward motion"}],"modalities":["audio"],"stream":true}' \
 | python3 -c "import sys,json,base64;b=''.join(json.loads(l[5:]).get('choices',[{}])[0].get('delta',{}).get('audio',{}).get('data','') for l in sys.stdin if l.startswith('data:') and 'DONE' not in l);open('bgm.mp3','wb').write(base64.b64decode(b))"
```

Mix under the video (trim to length, fade in/out, duck volume):

```bash
ffmpeg -y -i out.mp4 -i bgm.mp3 \
 -filter_complex "[1:a]atrim=0:12.5,afade=t=in:st=0:d=1.2,afade=t=out:st=11:d=1.5,volume=0.55[a]" \
 -map 0:v -map "[a]" -c:v copy -c:a aac -shortest out_music.mp4
```
