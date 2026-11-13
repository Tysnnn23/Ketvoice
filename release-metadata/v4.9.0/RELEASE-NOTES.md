# Ketvoice 4.9.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

## Liquid 27 — a fourth glass style

**Settings → Appearance → Glass preview** now offers **Liquid 27** next to Clair,
Dépoli and Opaque. It is a separate material (its own AGSL shader), so the three
existing styles render exactly as before.

It is modelled on the publicly described 2026 Liquid Glass refinements. Apple's
renderer is not public, so this is a close reproduction of the described and
observable behaviour, not a pixel-identical copy. No Apple fonts, symbols or
wallpapers are used.

- **Continuous corners.** Cards and sheets use a superellipse corner that stays
  inside the shape's clip. Capsules and circles stay true capsules.
- **Uniform refraction.** A convex lens of constant strength all around the edge
  (the normal is computed numerically, so corners do not bend harder than straight
  edges), with a slight magnification of the whole body.
- **Clear ↔ Tinted.** The existing transparency slider drives the material: almost
  no blur when clear, frosted and milkier when tinted. Choosing Liquid 27 for the
  first time starts the slider at its midpoint, the reference default; a value
  you set yourself is kept.
- **Defined borders, no drop shadow.** A thin lit rim faces the light, a softer
  bounce sits on the opposite edge, and there are no inner or drop shadows.
- **Lighter dark material** and **luminance-adaptive milk**: the backdrop under each
  control is measured (five taps) so the glass adapts to the content behind it.
- **Highlights follow tilt.** The light direction follows the gravity sensor at UI
  rate, ignores sub-degree jitter and rests at the top-left default with reduced
  motion, on Quest and with the other styles.
- **Touch glow.** Pressing a glass control spreads light from the finger and
  increases the lens pressure. Pointers are observed, never consumed, so every
  control keeps its own gestures.
- **Scroll-edge fade.** Content fades under the status bar and the floating chrome,
  inside the captured layer, so the glass refracts the faded content.

Tilt and touch values are read in the draw phase only: they redraw the glass layer
without recomposing the panel.

### Fallbacks

| Situation | Liquid 27 renders as |
| --- | --- |
| Android 13+ | Liquid 27 AGSL shader |
| Android 12, low-RAM device, reduced motion | Library blur and lens with the Liquid 27 border, no shadow |
| Glass disabled, performance mode, captured panel | Static material with the Liquid 27 border, no shadow |

### Not included

The liquid merging of separate glass elements (for example Home, mini-player and
Search joining like droplets during the collapse animation) needs every element in
one shader pass. It is not part of this release.

## Discord

- The Social SDK only delivers status, authorization, log and presence results from
  `discordpp::RunCallbacks()`. The bridge never called it, so no callback was ever
  delivered: presence confirmations, errors and the linked account's Ready state
  were never observed. A 50 ms main-thread pump now runs while a client exists. It
  does not hold the bridge mutex, because callbacks re-enter Kotlin.
- CI can now build Discord-enabled APKs without committing the SDK: a private AAR
  URL in a secret, its SHA-256 pinned in a repository variable, and a check that the
  release APKs contain `libdiscordbridge.so`.
- The OAuth User-Agent now reports the real app version.

## Fixes

- **Ignorer les silences** could never be turned on: the value travelled in the
  command extras while the service read the arguments, then saved `false`.
- **Privacy:** the website's "Listening now" receives the title, artist and cover
  only after you turn on **Partager le titre en cours sur le site** (Playback). The
  listener count itself stays anonymous.
- **Server:** the listener heartbeat is rate-limited, requires the app key, accepts
  only a well-formed client id, strips control and bidirectional characters, and
  keeps a cover only when it is a plain public HTTPS URL. `SECURITY.md` now
  describes the real `verify_jwt` configuration of `ketvoice-api`.
- **Storage:** the lyrics (64 MB), artwork (768 MB) and Canvas (512 MB) caches are
  now bounded, least recently used first. Covers and Canvas files that downloads use
  are never deleted.
- **Translations:** the Discord and System language strings added in 4.8.1–4.8.2
  are translated into all ten other languages, so the UI text audit that CI runs
  passes again. The artist-page accessibility label is now translated too.
- **Settings:** "Downloads on Wi-Fi only" moved from Appearance to Downloads.
- **CI:** the keystore generation workflow no longer prints the keystore in the job
  log; it is only in a one-day artifact.
- **Docs:** the Quest guide points to the latest release instead of 2.11.0.

## Validation

The signed APKs were built by GitHub Actions (run 43507175910) with the Discord
Social SDK, whose SHA-256 is pinned. Release builds, debug and release unit tests
for both flavors (487 mobile and 486 Quest host tests in the debug variants), the
UI text audit, lint, the signature pin and the Discord bridge packaging check
passed. The Liquid 27 AGSL shader is compiled and evaluated by a host render test.
