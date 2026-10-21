# Custom JSON Sources

Ketvoice 2.5.0 includes **Ketvoice API**, a built-in Supabase broker for music-source search, streams, and Spotify Canvas. It needs no user configuration and keeps its provider credentials on the server. The active built-in providers are Ultra MAX (`um`) and Last Babe (`last`). This document covers the optional custom JSON sources that can be added alongside them.

Custom sources can be added from user-provided HTTPS manifests. Add a source in **Search → Sources** or **Settings → Sources personnalisées**. The URL must begin with `https://` and end with `.json`.

A source is saved only after Ketvoice downloads the manifest and performs a real test search. The test response must be valid JSON. Sources that require POST, authentication, or cannot answer a basic search are rejected.

## Supported manifest shapes

### Direct manifest

```json
{
  "name": "My source",
  "baseUrl": "https://api.example.com",
  "search": "/search?q={query}&quality={quality}",
  "stream": "/stream/{id}?quality={quality}",
  "download": "/download/{id}?quality={quality}",
  "qualities": ["high", "low"],
  "allowDownloads": 1
}
```

Relative endpoints are resolved against `baseUrl`, or against the manifest directory when `baseUrl` is absent. Only HTTPS URLs are accepted.

### Nested endpoints

```json
{
  "name": "My source",
  "baseUrl": "https://api.example.com",
  "endpoints": {
    "search": "/search?q={query}",
    "stream": "/stream/{id}",
    "download": "/download/{id}"
  }
}
```

### BitChord-compatible add-on

A manifest served at `https://host/path/manifest.json` can omit the search and stream endpoints. Ketvoice then uses:

```text
GET /path/search?q={query}&quality={quality}
GET /path/stream/{id}?quality={quality}
```

The manifest may declare quality options in a `settings` array:

```json
{
  "name": "My add-on",
  "settings": [
    {
      "key": "quality",
      "default": "normal",
      "options": [
        { "value": "lossless" },
        { "value": "high" },
        { "value": "normal" }
      ]
    }
  ]
}
```

Ketvoice negotiates the requested tier against the declared values. `allowDownloads: 0` disables downloads for that source.

## Placeholders

| Placeholder | Value |
| --- | --- |
| `{query}` or `{q}` | URL-encoded search query |
| `{id}` | Track identifier returned by the source |
| `{quality}` | Negotiated quality value |
| `{title}` | URL-encoded track title |
| `{artist}` | URL-encoded artist |

## Search response

The response can be an array of tracks or an object containing an array under `tracks`, `results`, `data`, `songs`, `items`, or `streams`.

```json
{
  "tracks": [
    {
      "id": "track-123",
      "title": "Example title",
      "artist": "Example artist",
      "album": "Example album",
      "duration": 245,
      "artworkURL": "https://cdn.example.com/cover.jpg",
      "streamURL": "https://cdn.example.com/audio.flac"
    }
  ]
}
```

Recognized keys:

- Identifier: `id`, `_id`, `videoId`, `trackId`, `deezerId`, or `isrc`
- Title: `title`, `name`, `track`, or `song`
- Artist: `artist`, `artists`, `artistName`, `creator`, or `author`
- Album: `album`, `albumTitle`, `collection`, or `release`
- Duration: `duration` or `durationMs`, in seconds or milliseconds as appropriate
- Artwork: `artworkURL`, `artwork`, `cover`, `coverUrl`, or `image`
- Direct stream: `streamURL`, `streamUrl`, or `url`

A direct `streamURL` is used as-is after HTTPS validation. Otherwise, Ketvoice calls the stream endpoint with the track identifier.

## Stream response

A stream endpoint can return an object or an array. Ketvoice accepts fields such as:

- `url`, `streamURL`, `stream`, `audio`, or `playUrl`
- `download`, `downloadURL`, or `downloadUrl`
- `format`, `codec`, `container`, or `fileCodec`
- `quality`, `streamQuality`, or `audioQuality`

Only HTTPS stream URLs are accepted.

## Offline downloads

Downloads follow HTTPS redirects and retry interrupted responses using byte ranges when supported. The app validates audio signatures and complete known response lengths before committing the file. The list shows the format actually saved, including a lower-quality fallback when the selected quality is unavailable.

Available lyrics are retained as a JSON sidecar (including native word/syllable timing and provider translations) and an interoperable LRC file. Available Canvas media is stored in the shared persistent Canvas cache. Optional asset failures do not discard a completed audio download.

## Security and limitations

- Custom sources are third-party code endpoints, not sandboxed plugins. Only add sources you trust.
- The source can see your IP address, search queries, and requested track identifiers.
- Ketvoice does not send your YouTube session or any Spotify credentials to custom sources.
- Ketvoice API does not send your custom source URLs to its upstream providers.
- A source that changes its response format or becomes unavailable will disappear from results until it responds again.
