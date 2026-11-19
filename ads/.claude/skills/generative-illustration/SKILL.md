---
name: generative-illustration
description: This skill should be used when the user asks to "make an animated illustration", "animate an illustration", "make a generative motion piece" / "动态平面", "turn AI-generated art into something that moves", "make a poetic or ambient motion piece / motion poem", "make a visual metaphor", or "combine AI-generated visuals with code-driven motion". Covers generating isolated illustration assets with AI, compositing them as sprites, frame-accurate deterministic rendering, and the craft of expressive, concept-driven motion. NOT for data charts (use chart-animation), pure text (use kinetic-typography), or 3D/particles (use webgl-animation).
version: 0.1.0
---

# Generative Illustration

Make expressive, concept-driven pieces by combining **AI-generated illustration assets** with **code-driven motion**. This is the lane designers call 动态平面 / motion graphic design — built the agent-native way: an AI makes the visuals, code gives them life and meaning.

## When to use

- An animated illustration, a poetic / ambient "motion poem", a visual metaphor.
- A short brand or art piece where a beautiful illustrated scene moves *with intent*.
- Any "AI-made visuals + code motion" hybrid.
- **Not** for: data viz → use `chart-animation`; pure headline text → use `kinetic-typography`; 3D / GLSL / heavy particle systems → use `webgl-animation`.

## Setup / prerequisites

- **Rendering needs no API key** — Node + `playwright-core` (auto-fetches Chromium) + `ffmpeg`, all local and free. This covers the compose + animate + export core.
- **AI generation (assets + music) needs one API key.** The simplest path is a single **OpenRouter key** — set `OPENROUTER_API_KEY` — because one key covers all three generators this skill uses:
  - vectors → `recraft/recraft-v4.1-vector`
  - painterly textures → `google/gemini-2.5-flash-image`
  - music → `google/lyria-3-clip-preview` (audio needs `stream: true`)
- **Optional alternatives:** `REPLICATE_API_KEY` for cheaper Recraft (`recraft-ai/recraft-v4.1-svg`, ~$0.04 vs $0.08/image); a Google AI Studio `GEMINI_KEY` for Lyria direct.
- **Or bring your own assets/music** (hand-drawn, any AI tool) and skip generation entirely — the motion + craft core requires no key at all.

## The one rule that prevents 90% of failures

**Never try to animate one big AI-generated illustration. Generate many small *independent* elements and let code animate each as a whole unit.**

AI image/vector models output a **flat pile of ungrouped paths** — no `<g>`, no ids, no "this is the arm / these are the petals." You cannot rig a part inside it. Two failure modes follow:

- Treat the whole image as static and crossfade → **looks like a PowerPoint.**
- Auto-cluster the paths by proximity and wiggle each → **uncanny** (motion with no intent, everything wobbles).

> **The structure lives *between* assets, not inside one image.** Each element is its own clean file; code moves it whole. (This is how working motion-illustrators do it — design the pieces separately, then code organizes and presents them.)

## Pipeline

1. **Concept** — one metaphor, ruthless restraint.
2. **Generate each element separately** with AI, isolated on a plain background.
3. **Strip the background** → transparent sprite.
4. **Code the motion** — a deterministic seek-harness on a `<canvas>`; place + animate sprites + text + grain.
5. **Render** — frame-grab every frame → ffmpeg → MP4.
6. **Music** — generate a custom track (Lyria), mix it in.

## 1 · Generating assets

- **Crisp vector elements** (a flower, an icon, a creature) → **Recraft v4.1 SVG**.
- **Painterly textures** (a watercolor wash, paper grain) → an **image model** (Gemini "nano-banana").
- **Specificity wins.** Name a real, characterful subject — generic prompts give generic art:

| Prompt | Result |
|---|---|
| `a single delicate wildflower, soft petals…` | flat, generic, cartoonish |
| `a single **elegant poppy** on a **tall slender curved stem**, soft coral-red petals with a **delicate dark center**, one leaf, refined minimal flat vector, single isolated element, plain solid white background` | a graceful, recognizable poppy — instantly "designed" |

- Always add **`no text`** — AI mangles lettering. Add every word in code (where it is exact and editable).
- Ask for **"single isolated element, plain solid white background"** so it is trivial to cut out.
- **Strip the background path.** Recraft draws a full-canvas white rectangle as path #0. Detect the path whose bounding box covers ~the whole viewBox and delete it → transparent sprite:

```python
# keep every path EXCEPT the one whose bbox spans the whole canvas
def is_bg(path, VBW):
    nums = [float(n) for n in re.findall(r'-?\d+\.?\d*', d_of(path))]
    xs, ys = nums[0::2], nums[1::2]
    return xs and (max(xs)-min(xs)) > VBW*0.92 and (max(ys)-min(ys)) > VBW*0.92
```

- Recraft v4.1 SVG format: `<path …></path>` (not self-closing), `viewBox="0 0 2048 2048"`, `transform="translate(x,y)"`, absolute coordinates.

## 2 · The deterministic seek harness (the key to clean rendering)

Every visible value is a **pure function of time `t`** — never a `requestAnimationFrame` loop or a CSS transition (those desync from the frame-grabber and flicker).

```js
window.__T = totalSeconds;                 // total duration
window.__seek = (t) => render(t);          // bar width, x/y, opacity, scale — all derived from t
// on load: render the frame named in the URL
window.__seek(parseFloat(new URLSearchParams(location.search).get('t')) || 0);
```

A one-browser Playwright loop seeks `t = 0, 1/30, 2/30 …`, screenshots each, then ffmpeg assembles them (use **even** dimensions + `yuv420p`). For stateful sims (particles), step the sim forward deterministically with a **seeded RNG** so every render is identical.

## 3 · Animating sprites

- Load each asset as an `Image` (inline base64 data-URI so the HTML is self-contained); `drawImage` per instance inside `save/translate/rotate/scale/restore`.
- **Anchor each sprite at its motion pivot** (base / center / a corner — whatever the motion needs). E.g. to rise from a line: `translate(x, baseY)` then draw at `(-w/2, -h)`.
- Match the entrance to the idea, don't reflex to "grow": a count-up, a split/double, a slide-in, a flood, a snap, a scale-from-0 — each says something different. Idle life: gentle `sin(t)` drift.
- Add a **grain overlay** (seeded noise) for a hand-made, Softcore texture.
- In capture, gate on an `window.__ready()` flag once all images have decoded.

## 4 · Craft — what makes it *good*, not just moving

- **Let the concept choose the visual — never default to one device.** The motion vocabulary must be derived from *this idea*, not reused: compounding → a dot doubling until it floods the screen (not a plant); a blank page → the first word breaking a dam of text (not a sprout); overload → tabs/windows multiplying into noise. **Reusing one device — "a thing grows up from the ground" — across unrelated concepts is the #1 reason these go stale and generic.** If you reach for "something grows" a second time, stop and ask what the concept actually looks like. (The growing-plant in the original garden piece worked *only* because the concept was literally botanical.)
- **Causality.** Tie cause → effect literally (an input lands → something happens *at that spot*). Loosely-related parallel events read as random noise.
- **Restraint.** Motion needs fewer elements than static design — too many and it gets noisy in motion. Keep only the core; don't overdo it.
- **Spotlight the hero.** When one element matters (the one that "made it"), dim the rest and lift it — a halo, a scale, a color shift.
- **Rhythm: resolve → a beat of stillness → reflect.** A closing line is a *looking-back* statement; it only lands after the action has stopped. Let the motion finish, hold a beat, *then* reveal the slogan — never overlap the conclusion with things still moving.
- **Easing.** Never linear on anything a viewer reads. Ease the *value*, not just opacity. Overshoot for small pops; slow ease-out for growth.
- **Semantics — don't self-sabotage.** If the piece depicts failure/withering, do **not** label it with your own product's features (it reads as "our product fails at its core jobs"). Use neutral or process language instead.

## 5 · Music

Generate a custom track with **Lyria 3** (OpenRouter `google/lyria-3-clip-preview`). It is AI-generated, so it is royalty-free. **Audio output requires `stream: true`** — chunks arrive as base64 in `delta.audio.data`; concatenate and decode to MP3. Mix with ffmpeg: trim to length, fade in/out, duck the volume (~0.5) under any narration.

## 6 · Verify loop (do not skip)

Render the piece, build a **full-duration** contact sheet (sample frames across the *whole* timeline, not just the first seconds), and **read it yourself**: blank frames? overlapping rows? does the concept actually read? Fix and re-render. This deliver-and-verify loop is what separates "it ran" from "it's good."

## references/

- `asset-pipeline.md` — asset prompting, background-strip code, Recraft/Gemini format notes
- `render-harness.md` — full seek-harness HTML + Playwright capture + ffmpeg recipe
- `craft.md` — causality, rhythm, restraint, spotlight, with worked examples

---

*The craft behind iart.ai. Cost reference: a Recraft SVG ≈ $0.04, a Lyria track ≈ cents — a whole finished piece comes in under $1.*
