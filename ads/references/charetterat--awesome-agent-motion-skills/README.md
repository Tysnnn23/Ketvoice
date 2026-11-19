<div align="center">

# awesome-agent-motion-skills

**Tested motion design, video generation, and transition skills for coding agents (Claude Code / Codex / Cursor).**

Turn static agent-written code into living animations, cinematic launch videos, interactive morphing UI, and dynamic galleries.

> ⛔ **Not affiliated with Anthropic, Remotion, or any skill author listed.** Star counts are a live snapshot (2026-11-05).
> Every skill listed has been verified for installation syntax, stack dependency, and reproducible execution.

</div>

---

## ⚡ The Decision Matrix

Choose the exact skill for your use-case:

| Skill | Primary Focus | Rendering Stack | Output Format | ★ |
|---|---|---|---|---:|
| [huashu-art-motion](https://github.com/alchaincyf/huashu-art-motion) | Art & Gallery Motion | HTML5 / Canvas / SVG / CSS | Interactive Web Pages & Exportable Clips | 1909 |
| [transitions.dev](https://github.com/Jakubantalik/transitions.dev) | UI & Interaction Transitions | Framer Motion / Tailwind / GSAP | React / Web Component Micro-interactions | 4577 |
| [video-shotcraft](https://github.com/Vincentwei1021/video-shotcraft) | Cinematic Product Video | Remotion / React / Three.js | MP4 / ProRes Video (Rendered) | 10751 |
| [onetake](https://github.com/feitangyuan/onetake) | Continuous Camera Motion | HTML5 / GSAP / SVG | Seamless Interactive Presentation / Video | 1900 |
| [video-talkcraft](https://github.com/Vincentwei1021/video-talkcraft) | Voiceover Explainer Studio | Remotion / Whisper / TTS API | Narrated Explainer Videos (MP4) | 1410 |
| [motion-design-skill](https://github.com/LottieFiles/motion-design-skill) | Physics & Disney Animation Principles | Lottie / dotLottie / CSS Keyframes | Lottie JSON / CSS Keyframe animations | 1969 |
| [motionmaxxing](https://github.com/Tejashmakwana/motionmaxxing) | Motion Polish & Slop Cleaner | GSAP / Remotion / CSS | Refactored Animation Code | 86 |

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


### [huashu-art-motion](https://github.com/alchaincyf/huashu-art-motion)

> **Category:** Art & Gallery Motion · **Stars:** ★1,909 · **Stack:** `HTML5 / Canvas / SVG / CSS`

35 curated artistic animation styles (Monet, Egyptian, Cyberpunk, 8-bit Mario, Ukiyo-e) and 9 spoken commentary animation grammars. Direct code generation without cloud GPU rendering.

- **Quick Install:** `npx skills add alchaincyf/huashu-art-motion`
- **Best For:** Topic explainer videos, artistic galleries, storybook narration, retro game animations
- **Prerequisites:** None (runs purely in-browser & locally via agent-generated code)
- **⚠️ Limitations:** Audio-less output by default; voiceover must be synced separately.

---

### [transitions.dev](https://github.com/Jakubantalik/transitions.dev)

> **Category:** UI & Interaction Transitions · **Stars:** ★4,577 · **Stack:** `Framer Motion / Tailwind / GSAP`

Curated library of 43+ production-grade UI transitions (morphing trays, page sweeps, micro-interactions, modal dissolves) designed specifically for AI agents to embed into web apps.

- **Quick Install:** `npx skills add Jakubantalik/transitions.dev`
- **Best For:** SaaS dashboards, landing page interactive elements, native-feeling app screen transitions
- **Prerequisites:** Node.js environment; Framer Motion or GSAP installed in target project
- **⚠️ Limitations:** Focuses strictly on UI state transitions, not full-length narrative videos.

---

### [video-shotcraft](https://github.com/Vincentwei1021/video-shotcraft)

> **Category:** Cinematic Product Video · **Stars:** ★10,751 · **Stack:** `Remotion / React / Three.js`

Studio-grade cinematic video skill for Claude Code & Codex: 152 camera movement recipe cards and 209 motion previews. Generates complete code-driven Remotion video projects.

- **Quick Install:** `npx skills add Vincentwei1021/video-shotcraft`
- **Best For:** Hardware/software product launch videos, promotional 3D teasers, cinematic sizzle reels
- **Prerequisites:** Remotion environment; Chrome for server-side frame rendering
- **⚠️ Limitations:** Requires local render time via Remotion CLI; high CPU/GPU overhead during export.

---

### [onetake](https://github.com/feitangyuan/onetake)

> **Category:** Continuous Camera Motion · **Stars:** ★1,900 · **Stack:** `HTML5 / GSAP / SVG`

Continuous-camera motion films that never cut between slides. Every beat dynamically morphs from the previous frame in a single uninterrupted spatial journey.

- **Quick Install:** `npx skills add feitangyuan/onetake`
- **Best For:** Technical deep-dives, architecture pitch decks, investor presentations that flow continuously
- **Prerequisites:** Modern browser supporting SVG morphing and Web Animations API
- **⚠️ Limitations:** Requires strict script structuring; hard cuts are disallowed by design.

---

### [video-talkcraft](https://github.com/Vincentwei1021/video-talkcraft)

> **Category:** Voiceover Explainer Studio · **Stars:** ★1,410 · **Stack:** `Remotion / Whisper / TTS API`

Turns coding agents into a full motion-design studio for voiceover-driven explainers with word-level phonetic subtitle syncing, kinetic typography, and B-roll callouts.

- **Quick Install:** `npx skills add Vincentwei1021/video-talkcraft`
- **Best For:** Educational tutorials, YouTube/Bilibili tech breakdown videos, documentation explainers
- **Prerequisites:** Audio file or TTS provider key (OpenAI/ElevenLabs); Remotion CLI
- **⚠️ Limitations:** Heavy dependency on precise word-level audio alignment.

---

### [motion-design-skill](https://github.com/LottieFiles/motion-design-skill)

> **Category:** Physics & Disney Animation Principles · **Stars:** ★1,969 · **Stack:** `Lottie / dotLottie / CSS Keyframes`

Adapts Disney's 12 basic principles of animation (anticipation, squash & stretch, follow-through) and physics-based spring curves specifically into prompts and rules for AI agents.

- **Quick Install:** `npx skills add LottieFiles/motion-design-skill`
- **Best For:** Ensuring agent-generated web animations don't look robotic; organic icon & button physics
- **Prerequisites:** Lottie web player or native CSS animations
- **⚠️ Limitations:** Provides principles and curve definitions rather than full pre-built video templates.

---

### [motionmaxxing](https://github.com/Tejashmakwana/motionmaxxing)

> **Category:** Motion Polish & Slop Cleaner · **Stars:** ★86 · **Stack:** `GSAP / Remotion / CSS`

Agent skill focused on elevating AI-generated motion graphics: fixes pacing, adjusts spring dampening, eliminates jarring jump-cuts, and injects professional easing curves.

- **Quick Install:** `npx skills add Tejashmakwana/motionmaxxing`
- **Best For:** Second-pass audit and aesthetic upgrade for raw animations generated by LLMs
- **Prerequisites:** Compatible with any web animation framework (GSAP/CSS/Remotion)
- **⚠️ Limitations:** Designed as an optimization pass; does not generate initial compositions from scratch.

---

## 🛠️ Verification & Quality Gate

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
