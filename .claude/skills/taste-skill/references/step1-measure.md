# Step 1 — Measure (量体)

## Role for this step
Design measurement extractor. Extract precise, measurable design properties from the page data and the screenshot. Be a *meter*, not a critic — no interpretation, no "why", only "what" and "how much".

## Inputs

1. A full-page screenshot of the target URL.
2. Structured DOM/CSS snapshot from `extract.js` (passed in as JSON).

The screenshot is the primary source — it shows what a human actually sees. The DOM snapshot supplies exact numbers (px, hex) to make your measurements precise. When the two conflict, trust what you see in the screenshot over what the DOM reports. CSS contains many values that are technically present but visually insignificant (one-off gradients, icon fills, hover-only states); the screenshot reveals what is actually dominant.

## Visual primacy rules (read before extracting anything)

- **Color**: Before listing any colors, study the screenshot. Which colors are visually dominant across the full page? A color that appears in the DOM but occupies less than ~5% of visible surface area is decorative/incidental — note it separately, do NOT list it as a brand or palette color. Look especially for: gradient text that appears in one hero headline only; icon colors; avatar borders; colors that only appear on hover.
- **Layout structure**: Look at the screenshot to identify section boundaries. Are sections separated by horizontal rules, background color changes, whitespace only, or vertical/horizontal dividers? A "section gap" the DOM reports as 10px may visually look like 80px of breathing room — trust your eyes. Absence of dividers is also a finding worth noting explicitly.
- **Shadow and depth**: DOM shadow values are ground-truth for the CSS spec, but look at the screenshot to verify which shadows are actually perceivable. A `box-shadow: 0 1px 0 rgba(0,0,0,0.03)` is technically there but visually invisible — classify it as "imperceptible" rather than a design decision.

## What to extract

Return EXACT values (px, rem, hex, ratio) when possible. Use the DOM snapshot to get precise numbers; use the screenshot to determine what is visually real and significant.

1. **Color Palette** — every distinct color with its role (background, text, border, accent). Hex values.
2. **Font Families** — every unique family used and where it appears (display, body, mono).
3. **Font Size Scale** — every distinct font size, ordered largest to smallest.
4. **Font Weights** — every distinct weight used and where.
5. **Border Radius** — every distinct radius value and what component uses it.
6. **Shadow Types** — for each shadow: offset, blur, spread, color, layer count.
7. **Image Aspect Ratios** — ratios of hero / feature / thumbnail images.
8. **Card Aspect Ratios** — width:height ratios of card-like components.
9. **Spacing Scale** — base spacing unit and every spacing value observed (margins/paddings/gaps).
10. **Layout Grid** — number of columns, gutter width, container max-width.
11. **Section Spacing** — vertical space between major page sections.
12. **Component Reuse** — which UI components repeat (cards, buttons, nav items, badges).
13. **Text Alignment** — for each major section (hero, headings, body, nav, footer, cards), the text-align value. Identify the dominant alignment strategy.

## Additional measurements (record as facts alongside the 13 above)

14. **Neutral Color Tinting** — are the grays pure (`#888`, `#CCC`, `#F5F5F5`) or tinted toward a hue (warm/cool)? Record the hex and note "pure gray" vs "tinted toward [hue]".
15. **Shadow Construction** — for each shadow: how many layers? Is the shadow color pure black/gray `rgba(0,0,0,x)` or tinted toward a hue? Exact values.
16. **Nested Radius** — for any rounded component inside another rounded component (button inside card), record both parent and child radius.
17. **Typography System** — do the font sizes follow a mathematical scale (each step ~1.25× or ~1.5× the previous)? Does any text use `max-width` in `ch` units (character-based line length control)?
18. **Spacing Method** — `gap` or `margin`? Record which you observe.
19. **Motion Properties** — if transitions/animations are present, record which CSS properties animate (`transform`, `opacity`, `height`, `width`, etc.), the easing function, and the duration.
20. **Interaction Details** — record if you observe `:focus-visible` (vs plain `:focus`), `touch-action: manipulation` on buttons, URL params that reflect UI state (tabs, filters, pagination). The DOM snapshot has `focusVisible` and `reducedMotion` booleans — use them.

## Rules

- Be PRECISE. "12px" not "small". "16:9" not "wide".
- If you cannot determine an exact value, write `~approx` with your best estimate from the screenshot.
- Output as a structured list grouped by category. Group headings should match the 20 categories.
- **Max 5 lines of data per category.** No prose, no commentary — just values and roles. If a category has more than 5 items, keep the 5 most visually significant.
- Do NOT interpret or explain WHY. Only WHAT and HOW MUCH. Save the interpretation for Step 3.
- Cite specific evidence (selector, hex, px) for each finding — Step 2 needs concrete numbers to detect patterns.
- Use `domData.sizeDistribution`, `domData.weightDistribution`, `domData.spacingDistribution`, `domData.buttons`, and `domData.colors.backgroundColors[].areaPct` when available — they provide pre-counted frequency data.

## Output marker
Begin your output with the literal line:

```
### Step 1 Output — Measurements
```

so the orchestrator can find it in the conversation.
