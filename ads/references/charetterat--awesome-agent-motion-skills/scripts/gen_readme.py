#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Render README.md from data/skills.json.

Single source of truth: data/skills.json.
Running with --check exits non-zero if the rendered file has drifted,
serving as our mandatory CI gate.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "README.md")


def load(name):
    with open(os.path.join(ROOT, "data", name), encoding="utf-8") as f:
        return json.load(f)


def table(headers, rows, align=None):
    align = align or ["---"] * len(headers)
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(align) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def main():
    d = load("skills.json")
    skills = d["skills"]

    L = []
    L.append(f"""<div align="center">

# awesome-agent-motion-skills

**Tested motion design, video generation, and transition skills for coding agents (Claude Code / Codex / Cursor).**

Turn static agent-written code into living animations, cinematic launch videos, interactive morphing UI, and dynamic galleries.

> ⛔ **Not affiliated with Anthropic, Remotion, or any skill author listed.** Star counts are a live snapshot ({d['updated']}).
> Every skill listed has been verified for installation syntax, stack dependency, and reproducible execution.

</div>

---

## ⚡ The Decision Matrix

Choose the exact skill for your use-case:

{table(
    ["Skill", "Primary Focus", "Rendering Stack", "Output Format", "★"],
    [[f"[{s['name']}]({s['url']})",
      s["category"],
      s["primary_stack"],
      s["output_format"],
      s["stars"]] for s in skills],
    ["---", "---", "---", "---", "---:"])}

---

## 🎯 Which Skill Should You Pick?

- **Want to explain a topic with Monet / Cyberpunk / 8-bit Mario style?**
  👉 Use **[`huashu-art-motion`](https://github.com/alchaincyf/huashu-art-motion)** — Pure HTML5/Canvas, zero render time, instant browser animation.
- **Building a modern Web / SaaS app and need silky UI micro-interactions?**
  👉 Use **[`transitions.dev`](https://github.com/Jakubantalik/transitions.dev)** — 43+ ready-to-copy morphing UI transition recipes.
- **Need a high-budget 3D Apple-style product launch teaser?**
  👉 Use **[`video-shotcraft`](https://github.com/Vincentwei1021/video-shotcraft)** — 152 camera movement recipe cards driven by Remotion & Three.js.
- **Presenting a complex architecture without slide cuts?**
  👉 Use **[`onetake`](https://github.com/feitangyuan/onetake)** — Unbroken continuous-camera spatial animation.
- **Creating YouTube/Bilibili tech explainers synced to a voiceover?**
  👉 Use **[`video-talkcraft`](https://github.com/Vincentwei1021/video-talkcraft)** — Word-level kinetic subtitles & B-roll callouts.
- **Your agent's animations look stiff, robotic, or unnatural?**
  👉 Use **[`motion-design-skill`](https://github.com/LottieFiles/motion-design-skill)** (Disney 12 physics principles) or **[`motionmaxxing`](https://github.com/Tejashmakwana/motionmaxxing)** (automated easing curve refactor).

---

## 📦 Detailed Breakdown

""")

    for s in skills:
        L.append(f"""### [{s['name']}]({s['url']})

> **Category:** {s['category']} · **Stars:** ★{s['stars']:,} · **Stack:** `{s['primary_stack']}`

{s['what']}

- **Quick Install:** `{s['install_cmd']}`
- **Best For:** {s['best_for']}
- **Prerequisites:** {s['prerequisites']}
- **⚠️ Limitations:** {s['limitations']}

---
""")

    L.append("""## 🛠️ Verification & Quality Gate

Every skill cited in this repository is actively verified for reachability and token persistence:

```bash
python3 scripts/verify_sources.py
```

It ensures no dead links or phantom claims exist in this directory.

---

<div align="center">

**Data Source** — [`data/skills.json`](data/skills.json) · **Automated Validator** — [`scripts/verify_sources.py`](scripts/verify_sources.py)

<sub>README.md is generated automatically from data/skills.json. Edit the JSON, not this file.</sub>

</div>
""")

    text = "\n".join(L)

    if "--check" in sys.argv:
        cur = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if cur != text:
            sys.exit("README.md is stale — run: python3 scripts/gen_readme.py")
        print("README.md in sync")
        return

    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {OUT} ({len(text):,} chars)")


if __name__ == "__main__":
    main()
