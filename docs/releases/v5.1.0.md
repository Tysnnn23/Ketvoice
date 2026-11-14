# Ketvoice 5.1.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

Automix that tells you what it is doing, mixes albums too, and artist signatures that show up.

## Automix

- **It mixes albums too.** Albums played in order are no longer left alone: a new setting,
  *Mix albums too* (on by default), lets Automix mix them as well. An album whose titles run
  straight into each other, with no gap at all, is still left as it is.
- **It says why it is not mixing.** A line next to the Automix button in the queue, and in
  Settings → Playback → Automix, shows what Automix is doing: preparing the next title, the
  mix coming up in so many seconds and how it will be done, a mix in progress, or why there
  is none (repeat, sleep timer, bit-perfect output, last title of the queue…).
- **The next title is loaded early.** Preparation starts two and a half minutes before the end
  of a title, so the next one is resolved and read well before any mix begins; a title that
  cannot be found is tried again instead of being given up at once.
- **Stronger mixes.** Balanced mixes now run up to 16 seconds and can start up to 25 seconds
  into an instrumental ending; DJ mixes go up to 32 seconds and start as soon as the singing is
  over; crossfades are longer (8 s balanced, 12 s DJ).
- **A mix is never planned over the body of a title.** When there are no lyrics to say where
  the singing stops, the mix waits for the outro.

## Artists

- **Signatures show up again.** Signatures from the public signature archive are now asked
  for alongside the artwork service instead of after it: when that service is busy (it often
  answers "temporarily unavailable"), the signature is still shown. Files named after the
  artist with a possessive or a number ("Eminem's signature", "Travis Scott signature 01")
  are recognised too.
