# Ketvoice 4.12.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

Skip tracks from anywhere, a queue you can see and shape, and four screens brought up to date.

## Playback

- **Next and Previous outside the app.** The media notification, the lock screen,
  headset and Bluetooth buttons and the other system media controls can now skip to
  the next or the previous title. Until now they could only play and pause.
- **Shuffle you can see.** Turning shuffle on reorders the queue itself: the current
  title stays, every other title is shuffled, and the queue shows exactly what plays
  next. Turning it off puts the queue back in its original order. The Shuffle buttons
  of albums, playlists and mixes start on a random title.
- **A real Previous.** It returns to the title you actually came from. After the first
  three seconds it restarts the current title, as before.
- **Repeat the queue.** The repeat control now cycles through off, queue and title.
- **The next title is ready before the end.** In the last 25 seconds of a title, the
  stream of the next one is resolved, or resolved again if it was resolved too long
  ago, so the change of title does not wait for the network.
- **Sleep timer on phones.** In the title menu: 15, 30, 45 or 60 minutes with a short
  fade-out, or **At the end of the song**. It was only available on Quest.
- **Swipe to change title** on the mini-player and on the artwork of the player.

## Queue

- Reordering is live: the other rows move out of the way while you drag.
- Swipe a title aside, or tap ✕, to remove it, with **Undo**.
- **Clear** the queue, with Undo, and **Save** it as a playlist.
- On tablets and Quest, shuffle, repeat and autoplay are now inside the queue panel.

## One set of gestures in every list

Album, catalogue and mix pages, artist pages, Library, Downloads, playlists, Replay,
Local music and Search now behave the same way:

- swipe a title to **play it next** or **add it to the queue**, with an icon, a tick
  when releasing would act, and a spring back;
- long-press a title to open its menu;
- the title that is playing shows animated bars.

## Replay

- The time you really spent listening.
- Tapping an artist opens their page, or a search for their name. It used to open an
  empty search.
- Play, shuffle or save your most played titles as a playlist.

## Local music

- The screen now asks for the audio permission, explains why, and points to the app
  settings if it was refused. The permission was never requested, so the whole-device
  library looked empty.
- Search, sorting by title, artist, album or duration, **Titles, Albums and Artists**
  views, and Play and Shuffle buttons.

## Search

- An **Albums** shelf from the public catalogue.
- **Library** now searches downloads, playlists, local files and the titles you have
  played, and finds playlists by name. It only searched downloads.
- Results no longer blank at every letter typed.

## Explorer

- Trending songs, albums and artists from the catalogue chart, above the genres.

## Fixes

- "Play next", "Sleep timer", "Repeat song" and "plays counted" were mistranslated in
  the ten languages other than French.
- Repeat on a queue of a single title keeps repeating instead of stopping after the
  second play.

## Not included

Gapless playback and crossfade are not part of this release: the audio engine still
holds one title at a time.

## Validation

Host tests: 603 mobile and 602 Quest tests pass, with lint and the UI text audit.
