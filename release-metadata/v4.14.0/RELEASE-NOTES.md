# Ketvoice 4.14.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

One layout for album, playlist, artist and Downloads, and the rest of the interface
brought in line with it.

## One collection page

Album, playlist, artist and Downloads now share the same header:

- the colours of the cover, blurred, from edge to edge;
- the artwork floating over them as a square with depth (a circle for an artist, a
  mosaic of your latest covers for Downloads);
- the name, the artist and one line of facts, centred;
- **Play** and **Shuffle** as a pair, then the secondary actions.

On a wide or short window the artwork sits beside the text. The album no longer opens
on a full-width picture.

## A title bar that follows you

Once the header has scrolled away, the bar at the top shows the name of the page in a
glass capsule and a small Play button. Album, playlist and Downloads have it; the
artist page already kept its name and quick actions.

## Liquid 27: buttons of one piece

- **Play and Shuffle are one body of glass.** In the Liquid 27 style, pressing either
  one pulls a bridge of liquid between them; they part again when the finger lifts.
- **Menus grow out of what opened them.** The menu of a title, and the other sheets,
  now expand from the place you touched instead of sliding up from nowhere.
- **A sliding lens on selectors.** Titles / Albums / Artists in Downloads and Local
  music, and All / Sources / Library in Search, select with a lens of glass that
  slides from one choice to the next.

## The menu of a title

Five round glass buttons lead the menu: favourite, play next, add to queue, download
and share. The list below keeps the rest.

## One row for a title

Catalogue and mix pages, artist pages, Local music and Replay now draw a title with
the same row: cover with its download state, the format badge of a saved file,
animated bars while it plays, and the menu button. Swiping a row uncovers a capsule of
tinted glass instead of a flat band.

## Page opening

When a page is opened from a card, its artwork continues the movement: it starts at
the size of that card and settles into its place.

## Validation

Host tests: 613 mobile and 612 Quest tests pass, with lint and the UI text audit.
