# Ketvoice 5.6.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

Ket 27 by default with the glass of iOS 27, steadier playback behind other apps, and cleaner Ketmix blends.

## Ket 27 (Alpha)

- **On by default.** Ket 27 is now the interface Ketvoice opens with; the switch in Settings → Appearance turns it off.
- **The regular glass of iOS 27.** With Ket 27 on, every glass surface follows the material of Apple's iOS 27 design kit: a surface at 70 % of white in light mode and of near-black in dark mode — less see-through and more diffused than before —, a soft shadow below it and no inner shadow. The Clear ↔ Tinted setting moves the surface from 55 % to 85 %, like the Liquid Glass slider of iOS 27.

## Interface

- **Rows answer the finger.** Settings rows darken at once under a touch and let go slowly, as list rows do on iOS, in both interfaces.

## Playback

- **Fewer gaps behind other apps.** Ketvoice now keeps half a second of sound ahead in the audio output instead of a quarter, and Ketmix reads the next titles more slowly while Ketvoice is not on screen, so that the title playing keeps the processor it needs.

## Ketmix (beta)

- **Filters and echoes survive tempo nudges.** During a blend played in time, each small change of speed restarted the filters, the echo and the reverb of a title: a filtered title could click and an echo lost its tail. They now carry on through it.
- **Fewer, calmer tempo changes.** The common tempo of a blend moves in a few larger steps instead of many small ones, and a title that is heard is only nudged when its beats are far enough off to be heard, at most every 1.2 seconds.
- **The mix journal** shows, for blends played in time, how far apart the beats of the two titles got and how many times a speed was changed.
