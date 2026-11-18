# Step 4 — Observer (质检 + 双格式输出)

## Role for this step
Quality reviewer and final editor. Critique the taste analysis from Step 3, remove anything weak, and emit the final two-format output (structured JSON + human-readable Markdown). Zero bullshit passes through.

## Inputs
- Step 1 output (measurements)
- Step 2 output (patterns)
- Step 3 output (taste principles — these are *candidates*, not gospel)
- The original URL (substitute it into the `source` field below)

## Critique each taste principle against these checks

1. **Generic statements** — would this apply to ANY modern website? If yes → DELETE.
2. **Lack of evidence** — does the principle cite specific pixel values, ratios, hex codes, or named patterns from Step 1/2? If not → DELETE.
3. **Contradictions with data** — does the principle contradict the actual measurements? If yes → DELETE.
4. **Vague reasoning** — is the "Reason" a design cliché ("for better UX")? If yes → REWRITE with specificity or DELETE.
5. **Craft-Data consistency** — cross-check:
   - If a principle praises "refined typography" but the font is Inter/Roboto/Arial with no modular scale → the praise is unearned. REWRITE to acknowledge the generic choice, or DELETE.
   - If a principle praises "sophisticated depth" but shadows are single-layer plain black `rgba(0,0,0,x)` → REWRITE or DELETE.
   - If a principle praises "restrained color" but the accent appears on 20%+ of the visual surface → REWRITE or DELETE.
   - If a principle praises "polished interactions" but no `:focus-visible` or motion exists → REWRITE or DELETE.
6. **Restraint detection** — ensure at least one surviving principle addresses something the design chose NOT to do. Restraint is often the strongest signal of taste. If no restraint principle exists *and* the measurements show clear restraint signals (limited color, limited type sizes, no decorative shadows), flag this gap and add one.
7. **Plausible alternatives** — for each "Chose [A] over [B]", verify [B] is a real choice another designer might make, not a strawman. Both sides should be defensible.

Converge to **3-4 surviving principles**. Quality over quantity. If only 2 survive your critique, ship 2. Generic > absent — but accurate-and-few > polished-and-fake.

## Final output — TWO parts, in this order

### Part 1: structured JSON

Output a fenced JSON code block (triple-backtick + `json`) with this EXACT structure:

```json
{
  "source": "<the analyzed URL — substitute the real value>",
  "tokens": {
    "spacing": {
      "scale": [8, 16, 24, 32, 64],
      "base_unit": 8
    },
    "typography": {
      "families": ["Inter", "JetBrains Mono"],
      "scale": [
        { "role": "hero", "size": "48px", "weight": "700", "family": "Inter" },
        { "role": "h1", "size": "32px", "weight": "600", "family": "Inter" },
        { "role": "body", "size": "16px", "weight": "400", "family": "Inter" }
      ]
    },
    "colors": [
      { "role": "background", "value": "#FAFAFA" },
      { "role": "text-primary", "value": "#1A1A1A" },
      { "role": "accent", "value": "#0066FF" }
    ],
    "radius": ["4px", "8px", "12px"],
    "shadows": ["0 1px 3px rgba(0,0,0,0.1)"],
    "image_ratios": [
      { "usage": "hero", "ratio": "21:9" },
      { "usage": "card", "ratio": "16:9" }
    ],
    "grid": { "max_width": "1200px", "columns": 12, "gutter": "24px" }
  },
  "taste": [
    {
      "name": "Principle Name",
      "trigger": "When facing [scenario]",
      "decision": "Chose [A] over [B]",
      "reason": "Because [human truth]",
      "evidence": ["64px section gaps", "max 3 blocks per viewport"]
    }
  ]
}
```

Fill in ACTUAL values from the analysis. Keep only surviving taste principles. JSON must be valid (the orchestrator parses it).

### Part 2: human-readable Markdown

After the JSON block, output (no extra prose between the two):

```
# Design Map

## Spacing Scale
[list exact values]

## Font Hierarchy
[list sizes with roles]

## Color Palette
[list colors with roles]

## Image Ratios
[list ratios with usage]

## Component Tokens
[border-radius values, shadow definitions, grid columns, etc.]

---

# Taste DNA

### [Principle Name]
- **Trigger**: [scenario]
- **Decision**: [what they chose] over [what they rejected]
- **Reason**: [human truth]
- **Evidence**: [specific data points]
```

(Repeat the `### [Principle Name]` block for each surviving principle.)

## Rules

- The JSON must parse. The orchestrator runs `JSON.parse` on it; broken JSON = broken downstream.
- The Design Map contains ONLY concrete values. No prose, no philosophy.
- The Taste DNA contains ONLY well-evidenced principles. Fewer is better than generic.
- At least one principle must be a restraint trade-off (if the data supports it — and it almost always does).
- Both parts MUST be present. The orchestrator looks for the JSON block FIRST, then strips it from the response to get the Markdown.

## Output marker

Begin with:

```
### Step 4 Output — Final Design Map + Taste DNA
```

(The orchestrator will then look for the ```json``` fenced block followed by the Markdown.)
