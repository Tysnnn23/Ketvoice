# Ketvoice 3.0.0-Beta3

## 🐛 Critical Fixes
- **Fixed native crash when opening Artist page, Settings, Catalog, Downloads & Local Music screens** — the liquid-glass back button was reading the same backdrop layer that captures the screen it sits on, causing an infinite render recursion (RenderThread stack overflow). Glass panels inside captured content now render with a static translucent style (visually identical, no more feedback loop).
- Hardened artist page loading: all network/JSON/enrichment errors are caught with a retry screen instead of crashing.
- Fixed "Clear image cache" button in Settings — now actually clears the Coil image cache.

## ✨ Improvements
- Player progress bar now thickens (6→8dp) with a white handle while dragging for better feedback.

## 📦 Install
- `Ketvoice-3.0.0-Beta3.apk` — phones
- `Ketvoice-3.0.0-Beta3-Quest.apk` — Meta Quest
