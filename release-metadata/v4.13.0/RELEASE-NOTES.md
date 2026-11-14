# Ketvoice 4.13.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

One set of glass buttons for every collection, cover colours behind the headers, and a
navigation bar that takes a little more time to split.

## Glass buttons

- **Two buttons, everywhere.** The main action of a page is a **prominent** capsule
  tinted with the accent colour; the other actions are plain glass capsules of the
  same height. Album, playlist, artist, Downloads, Library, Local music and Replay now
  share this pair instead of five different pills.
- **Liquid 27 press.** In the Liquid 27 style a glass button swells evenly under the
  finger, its rim light turns towards the touch, and it settles with a small bounce
  when released. This follows the documented behaviour of interactive Liquid Glass;
  Apple does not publish its exact values, so it is a close reproduction. The other
  glass styles keep their press.
- The round glass buttons of the app (back, share, download, quick actions) get the
  same Liquid 27 press.

## Collection pages

- **Cover colours behind the header.** Playlist, artist and Downloads headers carry a
  soft glow taken from their cover. The glow is a layer of its own, so the glass
  buttons placed on it refract the artwork.
- **Playlist.** Play and Shuffle now lead as a pair, like on an album; reordering is a
  quieter third button. The cover gains depth.
- **Album.** Play is the prominent glass button, and the title that is playing shows
  animated bars in the track list.
- **Artist.** The portrait's colours continue under the name and the actions.
- **Downloads.** The summary card shows your three latest covers, and its storage bar
  is easier to read.

## Navigation bar

- The morph of the navigation bar, where Home, the mini-player and Search merge and
  split like droplets, is 3 % slower so it can be seen.

## Validation

Host tests: 608 mobile and 607 Quest tests pass, with lint and the UI text audit.
