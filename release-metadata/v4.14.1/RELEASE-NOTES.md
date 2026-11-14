# Ketvoice 4.14.1

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

The artist page, shown the way it should be.

## Artist page

- **The portrait leads again.** It fills the top of the page from edge to edge instead
  of floating in a circle.
- **The page takes its colour from the portrait.** The dominant colour of the photo
  tints the whole page: a pastel of it on a light theme, a deep shade on a dark one.
  The colour settles in once the photo is read.
- **No line where the photo ends.** The portrait dissolves into that same colour, so
  photo and page read as one surface.
- The name, the listeners and the Play and Shuffle pair are centred under the portrait.

The tint follows the existing **album colours** setting: with it off, the page keeps
its plain background. Album, playlist and Downloads keep the layout of 4.14.0.

## Validation

Host tests: 615 mobile and 614 Quest tests pass, with lint and the UI text audit.
