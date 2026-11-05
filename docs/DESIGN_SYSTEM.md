# Ketvoice Design System — Premium Redesign

This document describes the shared design primitives introduced for the premium
redesign. They are additive: every existing call site keeps working because the
new tokens have defaults and the legacy `Motion` API (`FADE`, `PRESS`,
`duration()`, `pressSpring()`, `reduced`) is preserved unchanged.

## 1. Shadows & reflections

### Elevation ladder (`GlassParams.elevationDp`)
One z-scale drives every glass shadow so surfaces read at consistent depths:

| Surface | Factory | Elevation | Shadow alpha (light / dark) |
|---------|---------|-----------|-----------------------------|
| Content card (matte) | `GlassParams.card` | 1 dp | 0.10 / 0.22 |
| Round button | `GlassParams.circle` | 4 dp | 0.14 / 0.26 |
| Floating pill / nav | `GlassParams.pill`, `navigation` | 8 dp | 0.16 / 0.30 |
| Bottom sheet | `GlassParams.sheet` | 16 dp | 0.20 / 0.40 |

- **Dynamic (AGSL) path**: shadow comes from the backdrop library (`Shadow.Default`).
- **Static path** (glass disabled / captured): a theme-aware `dropShadow` modifier
  now gives the panel a diffuse lift. Previously it had *no* outer shadow, which
  made disabled glass read as a flat painted rectangle. Dark mode deepens the
  alpha (×1.35) so frosted panels still detach from a near-black page.

### Shader reflections (AGSL, API 33+)
`GlassShader.kt` already computed Fresnel rim, lens-edge refraction with
chromatic dispersion, thickness shadow, caustic and a luminance-adaptive
legibility veil (auto-contrast). The premium pass adds:

- **Diagonal specular band** — a localized highlight sweeping top-left → centre,
  breaking the previous purely-vertical sheen so the panel reads as a tilted
  glass slab.
- **Bounce halo** — a faint light re-emerging at the bottom-right corner, the
  signature of light passing *through* a block rather than reflecting off it.

## 2. Coherent animations (`ui/motion/Motion.kt`)

All motion now derives from one object. Prefer the named scale over magic numbers:

```
Durations (ms):  FAST 150 · NORMAL 250 · MEDIUM 350 · SLOW 500 · EPILOGUE 700
Easing:          EASE_DECELERATE · EASE_ACCELERATE · EASE_STANDARD · EASE_OVERSHOOT
Springs:         snapSpring() · gentleSpring() · bouncySpring() · pressSpring(pressed)
Elevation (dp):  FLAT 0 · CARD 1 · RAISED 4 · FLOATING 8 · OVERLAY 16 · SYSTEM 24
```

Every helper clamps to `snap()` / 0 ms when **reduce motion** is active (system
setting or in-app toggle), matching the existing behavioural contract.

## 3. Automatic contrast (`theme/Contrast.kt`)

Glass samples whatever is behind it, so labels can silently lose legibility.
`Contrast` computes readability at runtime:

- `inkOn(background)` — high-opacity black or near-white ink that clears ~AA.
- `veilAlpha(background, darkInk)` — how much to veil the backdrop under a label.
- `contrastRatio(a, b)` / `meetsAA(fg, bg)` — WCAG checks (4.5:1).
- `blend(base, overlay, strength)` — keep alpha, tint on-brand.
- `adaptSurface(base, backdrop, dark)` — nudge the glass tone away from the
  backdrop luminance so light and dark material both stay separated.

The shader's own `veil` line implements the same idea on the GPU per-pixel.

## 4. Screen adaptation (`ui/adaptive/AdaptiveLayout.kt`)

Material3-style `WindowTier` (Compact / Medium / Expanded / Wide) plus new
responsive getters:

- `shortWindow` — landscape phones squeeze vertical chrome (smaller bottom pad).
- `nearSquare` — foldable inner / Quest windows.
- `trackColumns` — now 1 / 2 / **3** columns as width grows.
- `readingWidthDp` — caps long text columns at 720 dp on tablets.
- `pageGutterDp` — 16 → 20 → 24 → 32 dp gutters per tier.
- `generousInsets` — extra breathing room on large windows.

All existing getters (`hasRail`, `railWidthDp`, `playerPanes`, …) are preserved.

## 5. Dark & light modes

Both themes already flow through `LocalTheme`; the redesign keeps that contract
and adds:

- Elevation alpha split per mode (see §1) so shadows read in both themes.
- `Contrast.isLight()` / `Contrast.inkOn()` allow content laid over album art to
  switch ink automatically instead of hard-coding white or black.
- The static glass material base stays role-aware (Sheet is denser than Pill).

## Verification status

Compilation was **not** run in this environment (no JDK / Android SDK available
in this Termux session). Changes are additive with defaults and were reviewed
statically; build + device validation are still required before any release.
