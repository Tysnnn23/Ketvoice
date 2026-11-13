# Ketvoice 4.10.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

A design release: motion, Liquid Glass, formats and accessibility.

## Motion

- **Shared artwork.** Opening the player no longer just unmasks it: the artwork
  grows out of the mini-player cover and the controls rise into place, all on the
  one timeline that already drives the reveal, the drag-to-dismiss and the close.
- **Predictive back (Android 14+).** Holding the system back gesture shrinks the
  current page under the finger and slides it away from the swiped edge; in the
  player, it pulls the player toward the mini-player. Releasing commits, sliding
  back cancels.
- **Compact glass title.** When Listen Now, Explorer or Library is scrolled, its
  large title is echoed by a small glass capsule under the status bar, in step with
  the collapsing bottom chrome.
- **Haptics.** A light tick on glass buttons, tab changes, the released seek bar and
  a new favourite. One switch in Appearance; never on Quest.
- **Startup profile.** A baseline profile for Ketvoice's own startup and first-scroll
  code is now bundled and installed for sideloaded builds.

## Liquid Glass

- **Droplet fusion (Liquid 27).** Home, the mini-player and Search are now one glass
  body. At rest they are separate; while the chrome collapses or expands they pull
  liquid bridges and merge like droplets, then split again. Other styles and
  fallbacks keep three separate panels.
- **Droplet tab lens.** The selection lens leaves with its leading edge first and
  gathers its trailing edge afterwards, so it stretches between two tabs like a drop.
- **Cover-tinted glass.** The dominant colour of the playing cover lightly tints the
  optical glass (Clair, Dépoli and Liquid 27). The legibility stage runs after the
  tint, so labels keep their contrast. Optional.
- **Animated player background.** A slow mesh of the cover's colours drifts behind
  the player and breathes with the low end of the music. It is static with reduced
  motion and on Quest, and off in performance mode. Optional.

## Formats

- **Home-screen widget.** Now playing with cover, title and transport, in three
  responsive sizes, as a frosted rounded card. A widget cannot refract what is
  behind it, so it is frosted, not optical glass.
- **Tabletop posture.** On a flip-style phone half-opened on a table, the artwork
  and lyrics stay above the hinge and the controls below it.
- **Notification and lock screen** use the same enriched cover as the app.

## Accessibility and continuity

- Section titles are announced as headings, the play button reports its state, and
  the mini-player announces the new title when the track changes.
- Reduced motion now also follows the system *transition* animation scale.
- After Android reclaims the app in the background, Ketvoice reopens on the page and
  back stack you left, instead of the home tab.

## Validation

Host tests: 521 mobile and 520 Quest tests pass, with lint and the UI text audit.
The Liquid 27 fusion shader and the mesh shader are compiled and evaluated by host
render tests, and the fused chrome is exercised by a host interaction test.
