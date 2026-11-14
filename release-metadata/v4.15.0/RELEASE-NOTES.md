# Ketvoice 4.15.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

The album page takes the effect of the artist page.

## Album page

- **The cover leads.** The cover, or its Canvas, fills the top of the page from edge to
  edge and dissolves into the page without a line.
- **The page takes its colour from the cover.** The dominant colour of the cover tints
  the whole page: a pastel of it on a light theme, a deep shade on a dark one.
- **Sized with care.** The artwork is the full square of the cover when the window
  allows it, and never more than 46 % of the window height nor 420 dp, so the title
  and the Play button stay on the first screen.
- **Never a band of the cover.** On a window much wider than the artwork is tall, the
  cover is shown whole, centred over a soft enlarged copy of itself, instead of being
  cut to a strip.
- The name, the artist, the facts and the Play and Shuffle pair are centred under the
  artwork. A wide pane keeps the artwork beside the text.

The tint follows the **album colours** setting, as on the artist page.

## Validation

Host tests: 619 mobile and 618 Quest tests pass, with lint and the UI text audit.
