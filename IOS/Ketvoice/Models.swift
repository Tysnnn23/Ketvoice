import Foundation

struct Song: Codable, Identifiable, Hashable, Sendable {
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

struct LyricLine: Identifiable, Sendable {
    let time: TimeInterval
    let text: String
    var id: TimeInterval { time }
}

enum LRC {
    static func parse(_ content: String) -> [LyricLine] {
        let marker = /\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]/
        var offset: TimeInterval = 0
        if let match = content.firstMatch(of: /\[offset:([+-]?\d+)\]/) {
            offset = (Double(match.1) ?? 0) / 1000
        }
        let lines = content.split(whereSeparator: \.isNewline).flatMap { raw -> [LyricLine] in
            let line = String(raw)
            let matches = Array(line.matches(of: marker))
            guard !matches.isEmpty, let end = matches.last?.range.upperBound else { return [] }
            let text = String(line[end...]).trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty else { return [] }
            return matches.compactMap { match in
                guard let min = Int(match.1), let sec = Int(match.2), sec < 60 else { return nil }
                let fraction = match.3.map(String.init) ?? "0"
                let ms = Double("0." + fraction) ?? 0
                return LyricLine(time: max(0, Double(min * 60 + sec) + ms + offset), text: text)
            }
        }.sorted { $0.time < $1.time }
        // LRC can contain translations or repeated timestamps; keep stable SwiftUI identities.
        var result: [LyricLine] = []
        for line in lines {
            if let previous = result.last, previous.time == line.time {
                result[result.count - 1] = LyricLine(time: line.time, text: previous.text + "\n" + line.text)
            } else { result.append(line) }
        }
        return result
    }
}

struct PlaylistEntry: Codable, Identifiable, Hashable, Sendable {
    var id = UUID()
    let song: Song
}

struct LocalPlaylist: Codable, Identifiable, Hashable, Sendable {
    var id = UUID()
    var name: String
    var entries: [PlaylistEntry] = []
}

struct DownloadedSong: Codable, Identifiable, Sendable {
    var id: String { song.id }
    let song: Song
    /// A generated basename, never a remote URL or track identifier used as a path.
    let filename: String
}

struct LibraryState: Codable, Sendable {
    var favorites: [Song] = []
    var playlists: [LocalPlaylist] = []
    var downloads: [DownloadedSong] = []

    mutating func toggleFavorite(_ song: Song) {
        if favorites.contains(where: { $0.id == song.id }) { favorites.removeAll { $0.id == song.id } }
        else { favorites.insert(song, at: 0) }
    }
}

enum RepeatMode: String, CaseIterable, Sendable {
    case off, all, one
}
