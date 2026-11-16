# Ketvoice 5.10.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

Real voice separation in Ketmix, music that keeps playing behind other apps, and lyrics videos with sound.

## New

- **Ketmix really separates the voices.** Turn on *Separate voices* in Settings → Ketmix and download the separation model once (39 MB, optional). During a mix the current song's voice stops, the next song carries on over its own instrumental, then its voice comes in — no two voices over each other. The separation runs on the phone (Spleeter, an open AI model), at low priority, while the next mix is being prepared. Each song is separated only once: the result is kept in a tiny file (about 1 MB per song) and every later mix reuses it at once. Settings show how many songs are separated and the space they take, and can erase them or the model; separations can also be kept for when the phone is charging. Without the model, the voice is softened as before.
- **Lyrics videos with the song's sound.** A *Song's sound in the video* switch under the video button: the part of the song your lines come from plays under them, with short fades in and out.
- **Playback log** (Settings → Playback): what stopped the music, if it ever stops — a lost network, another app taking the sound, a call, headphones unplugged, or Android closing the app (with the reason Android gives). It stays on the phone and can be shared.
- **Your phone's own battery manager** (Settings → Playback, on Xiaomi, Huawei, Honor, Oppo, OnePlus, Realme, Vivo and Samsung): one tap to the screen where Ketvoice can be allowed to start and stay active in the background, which these phones require on top of Android's setting.

## Fixed

- **Music no longer stops behind other apps when the network drops for a moment** (a tunnel, Wi-Fi to mobile data, the phone asleep). The song waits for the network and starts again where it stopped, instead of every source being tried and failing one after the other until playback gave up.

## Faster

- **Lyrics videos are created much faster**: the Canvas behind the words is read in one pass instead of searched frame by frame, and each frame draws less.
- **Canvas videos no longer decode a sound track** nobody hears, leaving more of the phone to the music.

## Install

Download `Ketvoice-5.10.0.apk` (phone) or `Ketvoice-5.10.0-Quest.apk` (Meta Quest) below and install it over your current version: your library, settings and downloads stay.
