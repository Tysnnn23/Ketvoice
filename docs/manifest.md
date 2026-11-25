# Source manifest, version 2 (extended)

A source manifest is the `https://….json` file a listener adds in « Sources » (phone: Search › Sources or Settings;
Windows 2.0: Settings › Sources). Version 1 — `name`, `baseUrl`, `search`, `stream`, `download`, `qualities`,
`settings`, `allowDownloads` — is described in [SOURCES.md](SOURCES.md) and is read exactly as before.

Version 2 adds optional endpoints a source may declare: **lyrics**, **Canvas** and **covers**. A manifest without them
is a version 1 manifest. Older Ketvoice versions ignore the new fields, so a version 2 manifest still plays everywhere.
The parser lives in shared code (`app/.../data/CustomSources.kt` `parseManifest`, `CustomSourceExtras.kt`
`ManifestExtras`), used by the phone and by Windows.

## Example

```json
{
  "name": "My source",
  "manifestVersion": 2,
  "baseUrl": "https://api.example.com",
  "resources": ["search", "stream", "lyrics", "canvas", "cover"],
  "endpoints": {
    "search": "/search?q={query}&quality={quality}",
    "stream": "/stream/{id}?quality={quality}",
    "lyrics": "/lyrics?title={title}&artist={artist}&album={album}&duration={duration}&isrc={isrc}",
    "canvas": { "url": "/canvas?isrc={isrc}&title={title}&artist={artist}" },
    "cover": "https://img.example.com/cover?title={title}&artist={artist}&album={album}"
  },
  "qualities": ["lossless", "high", "low"]
}
```

## Fields

| Field | Where | Meaning |
|---|---|---|
| `manifestVersion` | top level | `2` for this schema. Optional: a manifest that declares any extended field counts as version 2. |
| `resources` | top level | What the source serves, among `search`, `stream`, `download`, `lyrics`, `canvas`, `cover` (strings, or objects with a `name`). An extended resource named here without an endpoint uses its default path (below). |
| `endpoints.lyrics` | `endpoints` object, or top-level `lyricsUrl` / `lyrics_url` | Lyrics for a title. |
| `endpoints.canvas` | `endpoints` object, or top-level `canvasUrl` / `canvas_url` | A short looping video (Canvas) for a title. |
| `endpoints.cover` | `endpoints` object, or top-level `coverUrl` / `cover_url` | A cover picture for a title. |
| `endpoints.search` | `endpoints` object or top level (version 1) | Search; its results are merged into the app's search. |

An endpoint is a string or an object with `url`, `path` or `endpoint`. It must start with `https://`, or with `/`
(then it is resolved against `baseUrl`). Anything else is ignored. A **top-level `cover` or `canvas` string is never
read as an endpoint**: version 1 manifests may already use those words for the source's own picture.

Default paths (used only when `resources` names the resource and no endpoint is given):

```text
/lyrics?title={title}&artist={artist}&album={album}&duration={duration}&isrc={isrc}
/canvas?title={title}&artist={artist}&album={album}&isrc={isrc}
/cover?title={title}&artist={artist}&album={album}&isrc={isrc}
```

### Placeholders

`{title}`, `{artist}` (the first credited artist), `{album}`, `{duration}` (seconds, empty when unknown), `{isrc}`
(empty when unknown), `{id}` (the source's own id of the title, when it came from that source), plus version 1's
`{query}`, `{q}`, `{quality}`. Values are URL-encoded.

## Answers

All requests are `GET` over HTTPS with no credentials. A source must answer within 12 s; a failure or an empty answer
just means « nothing from this source ».

**Lyrics** — one of, in this order of preference:

- JSON with `ttml` (TTML, word timing kept);
- JSON with `syncedLyrics`, `lrc` or `synced` (LRC text);
- JSON with `lines`: `[{"time": 1200, "text": "…"}]` (`time`, `timeMs` or `startTimeMs`, in ms);
- JSON with `plainLyrics`, `plain`, `lyrics` or `text` (unsynced);
- the same fields under a `data` object;
- or the raw body: LRC (starts with `[`), TTML (starts with `<`) or plain text.

**Canvas** — a JSON object with `canvas`, `canvasUrl`, `video`, `videoUrl`, `url` or `mp4` (also under `data`), a
plain `https://` address as the whole body, or the video itself (`Content-Type: video/*`, then the endpoint address
is the video). The video should be a short MP4 (H.264); it is downloaded once and kept with the other Canvas.

**Cover** — a JSON object with `cover`, `coverUrl`, `artwork`, `artworkUrl`, `image`, `url` or `picture` (also under
`data`), a plain `https://` address, or the picture itself (`Content-Type: image/*`).

Only `https://` addresses are used from any answer.

## How the app uses them

- **Lyrics**: an extra provider of the lyrics engine (`MusicApi.SRC_SOURCES`, shown as « Sources personnalisées »).
  In the automatic lookup it is asked together with the built-in providers and comes last in priority; with a listener
  order of providers it is tried after them. Enabled sources are asked in the listener's source order; the first one
  with lyrics wins.
- **Canvas**: used only when the usual Canvas lookup found none for the title (Windows), downloaded and kept like a
  linked Canvas.
- **Covers**: used only when the usual cover lookups found none for the title (phone and Windows).
- **Search**: results from every enabled source are merged into the search (phone; Windows since 2.0).

Disabled sources are never asked. The manifest's content, a source's answers and any quality choice stay on the
device; only the manifest address, the on/off switch and the order follow the account (see `CustomSourceSync.kt`).
