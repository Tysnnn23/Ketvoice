# Step 3 — Taste (推理品味)

## Role for this step
Design critic doing reverse engineering. You are NOT describing — you are identifying DESIGN TRADE-OFFS. Each taste principle answers: *what did this designer deliberately choose, and what did they reject?*

## Inputs
- Step 1 output (measurements)
- Step 2 output (patterns)

## What "taste" means here

Taste is visible in trade-offs. Identify decisions like:
- Clarity over density
- Speed over decoration
- Visual calm over expressiveness
- Personality over neutrality
- Consistency over variety
- Delight over efficiency

Each principle requires a **plausible rejected alternative**. Both sides of the trade-off must be defensible design choices. "Chose readable text over unreadable text" is not a trade-off. "Chose a single font family in 3 weights over a display/body font pairing" is a trade-off — both options are legitimate.

## Trade-off types — look for all three

**Restraint trade-offs** — where the designer COULD have added something but chose not to:
- Could have used accent color more broadly, but restricted it to CTAs only
- Could have enclosed every content block in a card, but trusted whitespace and typography to create grouping
- Could have used multiple font families, but committed to one across all roles
- Could have added decorative shadows / borders / gradients, but chose flat surfaces

**Craft trade-offs** — where the designer invested effort in subtle refinements:
- Tinted neutrals toward brand hue vs. pure gray
- Multi-layer shadows simulating real light vs. single flat shadow
- Child border-radius smaller than parent (concentric curves) vs. same radius everywhere
- Mathematical spacing system vs. arbitrary spacing
- Controlled line length (max-width in ch) vs. full-width text

**System trade-offs** — where the designer chose consistency rules over local optimization:
- Strict spacing grid (every value a multiple of base unit) vs. flexible per-context spacing
- Limited type scale (4-5 sizes) vs. many sizes for fine control
- Single accent color vs. multiple colors for variety

## Output format

For EACH taste principle, return exactly this structure:

```
### [Principle Name]
- **Trigger**: When the designers faced [specific scenario]…
- **Decision**: They chose [specific direction] over [the rejected alternative]
- **Reason**: Because [human truth — what readers actually feel/need, not design-industry convention]
- **Evidence**: [specific measurements / patterns from Step 1 and Step 2 that prove this]
```

Generate 4-6 candidates, then converge to the best 4 in your final output. Each MUST reference concrete evidence from Step 1 and Step 2. **During the candidate phase, write each candidate as a single line** (Name — Decision — key evidence). Only expand the final 4 into the full Trigger/Decision/Reason/Evidence structure.

**MANDATORY**: at least one of your 4 principles MUST be a *restraint* trade-off — something this design deliberately chose NOT to do that comparable sites typically do. Look for absent elements: no illustrations, no dark mode, no hero background images, no decorative gradients, no multiple font families, no animation, no card shadows. The absence of something common IS a design decision.

## Banned phrases (these signal you're describing, not analyzing)

- "clean", "modern", "sleek"
- "visually appealing", "user-friendly"
- "intuitive design", "seamless experience"
- "elegant", "minimalist" (without specifics)
- "polished", "refined" (without specifics)
- "sophisticated", "delightful", "stunning"
- "beautiful", "premium" (without specifics)

If you catch yourself reaching for these, stop and ask: *what is the actual trade-off here?*

**Failure example**: "Clean and modern aesthetic"
**Success example**: "Chose 64px section gaps to create reading pauses between content blocks — accepts longer scroll depth in exchange for forcing the eye to rest between ideas"

## Output marker

Begin with:

```
### Step 3 Output — Taste Principles (candidates → final 4)
```
