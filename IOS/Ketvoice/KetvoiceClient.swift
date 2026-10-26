import Foundation

enum APIError: LocalizedError {
    case configuration, response, invalidStream

    var errorDescription: String? {
        switch self {
        case .configuration: "Renseignez une clé publique Supabase pour activer le catalogue."
        case .response: "Le service musical est indisponible. Réessayez plus tard."
        case .invalidStream: "Ce titre n'a pas de flux compatible."
        }
    }
}

/// Only public catalog endpoints. Owner authentication and mutations are intentionally separate.
struct KetvoiceClient: Sendable {
    private let base: URL?
    private let publishableKey: String

    private struct PublicCatalog: Decodable { let url: String; let publishableKey: String }

    init(bundle: Bundle = .main) {
        let config = bundle.url(forResource: "PublicCatalog", withExtension: "json")
            .flatMap { try? Data(contentsOf: $0) }
            .flatMap { try? JSONDecoder().decode(PublicCatalog.self, from: $0) }
        let configuredBase = bundle.object(forInfoDictionaryKey: "KetvoiceAPIURL") as? String
        base = (configuredBase?.hasPrefix("https://") == true ? configuredBase : config?.url).flatMap(URL.init(string:))
        let override = bundle.object(forInfoDictionaryKey: "KetvoicePublishableKey") as? String ?? ""
        publishableKey = override.isEmpty || override.hasPrefix("$(") ? config?.publishableKey ?? "" : override
    }

    init(base: URL, publishableKey: String) {
        self.base = base
        self.publishableKey = publishableKey
    }

    private func get<T: Decodable & Sendable>(_ service: String, _ path: String,
                                             query: [URLQueryItem] = []) async throws -> T {
        guard let base, base.scheme == "https", !publishableKey.isEmpty else { throw APIError.configuration }
        var components = URLComponents(url: base.appending(path: "functions/v1/\(service)\(path)"), resolvingAgainstBaseURL: false)
        components?.queryItems = query.isEmpty ? nil : query
        guard let url = components?.url else { throw APIError.response }
        var request = URLRequest(url: url)
        request.setValue(publishableKey, forHTTPHeaderField: "apikey")
        // New publishable keys are API keys, not JWTs; the legacy anon key is a JWT.
        if !publishableKey.hasPrefix("sb_publishable_") {
            request.setValue("Bearer \(publishableKey)", forHTTPHeaderField: "Authorization")
        }
        request.timeoutInterval = 45
        let (data, response) = try await URLSession.shared.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              data.count < 8_388_608 else { throw APIError.response }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private struct Tracks: Decodable, Sendable { let tracks: [Song] }
    private struct Track: Decodable, Sendable { let track: Song }
    private struct Stream: Decodable, Sendable { let url: String }
    private struct Lyrics: Decodable, Sendable {
        struct Version: Decodable, Sendable { let format: String; let content: String }
        let versions: [Version]
    }

    func search(_ term: String) async throws -> [Song] {
        guard base?.scheme == "https", !publishableKey.isEmpty else { throw APIError.configuration }
        let query = [URLQueryItem(name: "q", value: term)]
        async let uploaded: Tracks? = try? get("ketvoice-content", "/search", query: query)
        async let addons: Tracks? = try? get("ketvoice-api", "/search", query: query)
        let responses = await [uploaded, addons].compactMap { $0 }
        try Task.checkCancellation()
        if responses.isEmpty { throw APIError.response }
        var seen = Set<String>()
        return responses.flatMap(\.tracks).filter { seen.insert($0.id).inserted }
    }

    func stream(for track: Song) async throws -> URL {
        if track.source == "upload" || track.id.hasPrefix("kv:upload:") {
            let identifier = track.addonId ?? String(track.id.dropFirst("kv:upload:".count))
            let result: Track = try await get("ketvoice-content", "/track", query: [URLQueryItem(name: "id", value: identifier)])
            guard let address = result.track.streamURL, let url = URL(string: address),
                  url.scheme == "https", url.host == base?.host,
                  url.path.hasPrefix("/storage/v1/object/public/ketvoice-public-media/") else {
                throw APIError.invalidStream
            }
            return url
        }
        guard let source = track.source, let id = track.addonId else { throw APIError.invalidStream }
        let result: Stream = try await get("ketvoice-api", "/stream", query: [
            URLQueryItem(name: "source", value: source), URLQueryItem(name: "id", value: id),
            URLQueryItem(name: "title", value: track.title), URLQueryItem(name: "artist", value: track.artist)
        ])
        guard let url = URL(string: result.url), url.scheme == "https" else { throw APIError.invalidStream }
        return url
    }

    func lyrics(for track: Song) async throws -> [LyricLine] {
        let response: Lyrics = try await get("ketvoice-content", "/lyrics", query: [
            URLQueryItem(name: "trackId", value: track.id), URLQueryItem(name: "title", value: track.title),
            URLQueryItem(name: "artist", value: track.artist),
            URLQueryItem(name: "durationMs", value: String(track.durationMs))
        ])
        return response.versions.filter { $0.format == "lrc" && $0.content.utf8.count < 1_048_576 }
            .map { LRC.parse($0.content) }.max(by: { $0.count < $1.count }) ?? []
    }

    func canvas(for track: Song) async throws -> URL {
        let result: Stream = try await get("ketvoice-api", "/canvas", query: [
            URLQueryItem(name: "title", value: track.title), URLQueryItem(name: "artist", value: track.artist)
        ])
        guard let url = URL(string: result.url), url.scheme == "https" else { throw APIError.invalidStream }
        return url
    }
}
