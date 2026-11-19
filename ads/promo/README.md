# Ketvoice ad (9:16, 60 fps)

A 34.5 s vertical ad made with Remotion and React Three Fiber: a 3D Android phone plays the owner's screen
recordings, cut on the beat of a 124 BPM track, with motion-designed pieces of the app (voice slider, Ketmix
sign, live mix waves, editor card) and sound effects.

## Assets (not in Git)

| Path in `public/` | Source |
|---|---|
| `models/phone.glb` | "Off-brand Android Smartphone" by SabasGT, Sketchfab, CC BY 4.0 (credited on the last screen; colours changed in `src/three/Phone.tsx`) |
| `clips/*.mp4` | the owner's screen recordings (`../captures/clip1..7.mp4`), re-encoded at 60 fps CFR and cut per scene (see below) |
| `music/track.wav` | ElevenLabs Music take C (`../music/music-C.mp3`), from 13.553 s for 33.9 s |
| `sfx/*.mp3` | ElevenLabs sound effects: whoosh, riser, impact, tap, shimmer, slider, filter-sweep, logo-sting |
| `fonts/inter_variable.ttf`, `img/ic_launcher27_glass_fg.png` | copied from the app (`app/src/main/res`) |

Scene cuts (`ffmpeg -ss <start> -t <len> -i captures/clipN.mp4 -an -vf fps=60,scale=870:1920 -crf 17`):
`s_home` clip1 0–5.2, `s_player` clip2 2.0–6.1, `s_lyrics` clip7 0.4–4.6, `s_stats` clip6 0.2–2.5,
`s_ketmix` clip5 2.6–8.6, `s_editor` clip4 1.3–6.7, `m1` clip3 3.0, `m2` clip1 6.4, `m3` clip5 11.0, `m4` clip3 12.0 (0.8 s each).

## Timing

`src/timing.ts`: drop 1 at 1.935 s, breakdown at beat 28.95, drop 2 at beat 32, CTA at beat 56, credits after beat 64.

## Render

```
npm install
npx remotion still src/index.ts KetvoiceAd out/check.png --frame 600 --scale 0.4
npx remotion render src/index.ts KetvoiceAd out/ketvoice-ad.mp4 --codec h264 --crf 16
```
WebGL runs on SwiftShader (`remotion.config.ts`: `swangle`, Playwright's headless shell); no GPU is needed.
