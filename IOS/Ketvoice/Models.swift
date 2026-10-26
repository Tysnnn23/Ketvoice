import Foundation

struct Song: Codable, Identifiable, Hashable {
    let id: String
    let title: String
    let artist: String
    let album: String
    let durationMs: Int64
    let artworkURL: String?
    let source: String?
    let addonId: String?
    let streamURL: String?

    init(id: String, title: String, artist: String, album: String = "", durationMs: Int64 = 0,
         artworkURL: String? = nil, source: String? = nil, addonId: String? = nil,
         streamURL: String? = nil) {
        self.id = id; self.title = title; self.artist = artist; self.album = album
        self.durationMs = durationMs; self.artworkURL = artworkURL; self.source = source
        self.addonId = addonId; self.streamURL = streamURL
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, artist, album, durationMs, artworkURL, source, addonId, streamURL
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        title = try values.decode(String.self, forKey: .title)
        artist = try values.decode(String.self, forKey: .artist)
        album = try values.decodeIfPresent(String.self, forKey: .album) ?? ""
        durationMs = try values.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0
        artworkURL = try values.decodeIfPresent(String.self, forKey: .artworkURL)
        source = try values.decodeIfPresent(String.self, forKey: .source)
        addonId = try values.decodeIfPresent(String.self, forKey: .addonId)
        streamURL = try values.decodeIfPresent(String.self, forKey: .streamURL)
    }
}

struct LyricLine: Identifiable {
    let time: TimeInterval
    let text: String
    var id: TimeInterval { time }
}

enum LRC {
    static func parse(_ content: String) -> [LyricLine] {
        let marker = /\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]/
        return content.split(whereSeparator: \.isNewline).flatMap { raw -> [LyricLine] in
            let line = String(raw)
            let matches = Array(line.matches(of: marker))
            guard !matches.isEmpty, let end = matches.last?.range.upperBound else { return [] }
            let text = String(line[end...]).trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty else { return [] }
            return matches.compactMap { match in
                guard let min = Int(match.1), let sec = Int(match.2) else { return nil }
                let fraction = match.3.map(String.init) ?? "0"
                let ms = Double("0." + fraction) ?? 0
                return LyricLine(time: Double(min * 60 + sec) + ms, text: text)
            }
        }.sorted { $0.time < $1.time }
    }
}
