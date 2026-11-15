# Ketvoice 5.2.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

Ketmix (beta): transitions that are more than a fade, a new dark theme and a new Home.

## Ketmix (beta), the new name of Automix

- **More than a fade.** Ketmix now chooses between six ways of going from one title to the
  next, from what it measured of the two:
  - *Matched to the tempo* — both titles in time, the bass handed over on a beat. The two
    tempos now meet during the mix: each title is only stretched while it is the quiet one,
    and the incoming title ends at its own tempo. After the bass swap the outgoing title
    thins out, rising into the air or sinking, in turn.
  - *Echo out* — when two tempos cannot be shared, the last beat is caught in an echo, the
    title is cut on the bar and the next one starts on that beat.
  - *Filter crossing* — an ending that fades or rings out is crossed by spectrum rather than
    by level: one title thins out while the other opens up.
  - *Dissolve* — a title without a beat dissolves into its own reverberation while the next
    one rises under it.
  - *Cut on the beat* — a last note is never covered; the first beat of the next title lands
    on the beat that follows it.
  - A plain crossfade is only left for when nothing finer can be played.
- **Keys.** Ketmix reads the key of each title. Two keys that clash are not left to overlap
  for long: the mix is shorter and thinner. The status line shows what was measured, for
  example `124 → 128 BPM · 8A → 9A`.
- **Songs come first.** Tempos must now be within 4 %, 6 % or 8 % (discreet, balanced, DJ)
  instead of 4 %, 8 % and 12 %. Balanced leaves out 16 seconds of an instrumental ending at
  most (25 before), DJ 45 seconds (90 before); discreet never leaves anything out and does
  without echoes. Two transitions in a row do not sound alike.
- **Three analysis settings.** Settings → Playback → Ketmix → *Title analysis* sets how much of
  the phone's processor goes into listening to titles before a mix: *Light* (30 seconds per
  title, tempo and levels), *Balanced* (45 seconds and the key, the default) or *Maximum*
  (75 seconds, the key, and a check that the tempo holds).
- **The switch moved.** In the queue, Ketmix is now the first button of the bar under the
  transport, left of shuffle, repeat and autoplay. Its status line sits above the progress
  bar; nothing floats over the list any more.

## A new dark theme

- A neutral near-black instead of the blue-grey one, three steps of surface above it, softer
  separators and calmer secondary text.
- Pages are lit from above by a soft glow of the accent colour that stays in place while the
  content scrolls.

## A new Home

- The page opens on a large card (a paused title with how far it had got, a fresh release or
  the mix of the hour) with a glow of its own artwork's colour.
- Shortcuts are tiles that say how much is behind them; recent titles are tiles to start
  again from (a long press opens a title's menu), or large covers with the toggle.
- *Your week* shows the seven days as bars, today in the accent colour.

## Lyrics

- The three dots before the first line fill one after the other without steps, breathe while
  they wait, then swell and vanish as the line arrives.

## Albums

- An album page takes its colour from the Canvas while one plays at its top, and from the
  cover as soon as there is none.
