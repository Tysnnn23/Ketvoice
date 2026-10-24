# Playlists and Spotify import

## Local playlists

Playlists live on the device in `files/playlists-v1.json` and are written atomically
(temporary file + rename). Limits: 100 playlists, 500 entries each, 16 MB.

Rules enforced by `PlaylistRules`:

- A name is trimmed, must be 1–80 characters and may not contain control characters.
- An entry stores the song identity and metadata, never an expiring `streamUrl`
  or `downloadUrl`. Streaming is resolved again at playback time.
- Reordering is bounded; an invalid move is a no-op and never drops entries.
- `Monter` / `Descendre` and `Retirer le titre` are explicit buttons, so a drag
  gesture cannot silently reorder a list.

A corrupt file sets a visible load error and is left untouched: the app never
replaces user data with an empty file to hide the problem.

## Spotify import

Entry point: Library → **Importer depuis Spotify**, next to **Créer une playlist**.

1. Paste a public playlist link: `https://open.spotify.com/playlist/<id>`,
   `https://open.spotify.com/intl-xx/playlist/<id>` or `spotify:playlist:<id>`.
2. Ketvoice fetches the same public preview page a browser receives
   (`/embed/playlist/<id>`), capped at 2 MB, no redirects followed, HTTPS only and
   only to `open.spotify.com`.
3. The preview lists at most 200 titles. Spotify does not guarantee completeness,
   so the dialog states it and requires an explicit checkbox before creation.
4. Each title is matched with `MusicApi.searchYtMusic` (2 concurrent searches) and
   accepted only when the normalized title is identical, the main artist appears in
   the candidate artist field, the candidate comes from the official song catalog and
   the duration difference is within max(8 s, 1/12 of the Spotify duration).
5. Matched titles are playable; unmatched titles remain in the playlist as
   **Non retrouvé · choisir une version** and can be resolved from search.

Nothing is sent to Spotify beyond the public page request. No Spotify account,
cookie, token, audio file or 30-second preview is used or stored.

## Cover

`PlaylistArtwork` draws an original Ketvoice composition: a deep gradient, measured
concentric rings and a baseline stroke, with the words `SPOTIFY` / `IMPORT` or
`KETVOICE` / the playlist initial. When the public preview exposes an artwork URL on
`i.scdn.co` or `mosaic.scdn.co`, it is used as a dimmed background only. No official
Spotify logo is reproduced.
