# Step 2 — Pattern (找规律)

## Role for this step
Pattern detector. From Step 1's measurements, identify SYSTEMATIC RULES — not individual values, but repeated design decisions that govern the whole surface.

## Inputs
- Step 1 output (the measurements).

(No screenshot needed for this step — work from the numbers.)

## What counts as a pattern

A pattern is a **governed rule**, not a single observation:
- "H1 is 48px" → observation.
- "Type sizes follow a 1.25× scale; every component sits on an 8px grid" → pattern.

Look for repetition, ratios, and constraints that hold across the whole page.

## Pattern categories

1. **Layout Density** — sparse or dense? How much whitespace per content?
2. **Spacing Rhythm** — consistent 8px grid? Variable? Ratio of small : medium : large gaps?
3. **Component Repetition** — which components repeat and how consistently (same size, same spacing)?
4. **Image Usage Patterns** — decorative, functional, atmospheric? Consistent sizing?
5. **Content Grouping** — cards? lists? sections? overlap between groups?
6. **Visual Hierarchy** — how many emphasis levels? Sharp hierarchy or flat?
7. **Contrast Strategy** — high contrast or subtle? Where is contrast concentrated?
8. **Alignment Consistency** — predominantly left, center, or mixed? Does alignment change between sections (centered hero, left-aligned body)? Deliberate compositional choice?

## Additional pattern categories (use these when the measurements support them)

9. **Spacing System** — strict mathematical base (every value is a multiple of 4px? 8px?)? Or arbitrary? Ratio of section-level gaps to component-level gaps?
10. **Typography Scale** — consistent ratio (1.25×, 1.333×, 1.5×)? Or arbitrary jumps? Tight system (4-5 sizes) or sprawling (8+)?
11. **Color Restraint** — accent color used sparingly (only CTAs / key actions) or broadly (headers, borders, backgrounds, icons)? What rough share of the visual surface uses accent vs. neutrals?
12. **Shadow & Depth System** — consistent across similar components? Single-layer or multi-layer? Shadow color tinted to match the surface or plain black?
13. **Card Usage** — cards used only when content is actionable/comparable, or as a default container for everything? Cards nested inside cards?
14. **Motion Quality** — if motion exists: only `transform`/`opacity` animated (performant), or layout properties like `width`/`height` (janky)? Easing exponential (`ease-out-quart/quint`) or generic (`ease`, `linear`)? Is `prefers-reduced-motion` respected?

## Output format

For EACH pattern, return exactly this structure:

```
### [Pattern Name]
- **Pattern**: [one-sentence description of the rule]
- **Evidence**: [specific measurements from Step 1 that prove this rule]
- **Design Goal**: [what this pattern achieves for the user — not the philosophy, the function]
```

Return 5-8 patterns. Every pattern MUST cite concrete evidence from Step 1. If you can't tie a pattern to numbers, drop it. **Each pattern block must be ≤ 4 lines** (Pattern + Evidence + Design Goal + optional one-line note). No prose paragraphs.

## Output marker

Begin with:

```
### Step 2 Output — Patterns
```
