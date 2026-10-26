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

struct KetvoiceClient {
    private let base: URL?
    private let publishableKey: String

    init(bundle: Bundle = .main) {
        base = (bundle.object(forInfoDictionaryKey: "KetvoiceAPIURL") as? String).flatMap(URL.init(string:))
        publishableKey = bundle.object(forInfoDictionaryKey: "KetvoicePublishableKey") as? String ?? ""
    }

    private func json(_ service: String, _ path: String, query: [URLQueryItem] = []) async throws -> [String: Any] {
        guard let base, base.scheme == "https", !publishableKey.isEmpty else { throw APIError.configuration }
        var components = URLComponents(url: base.appending(path: "/functions/v1/\(service)\(path)"), resolvingAgainstBaseURL: false)
        components?.queryItems = query.isEmpty ? nil : query
        guard let url = components?.url else { throw APIError.response }
        var request = URLRequest(url: url)
        request.setValue(publishableKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(publishableKey)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              let body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw APIError.response
        }
        return body
    }

    func search(_ term: String) async throws -> [Song] {
        let query = [URLQueryItem(name: "q", value: term)]
        async let uploaded = json("ketvoice-content", "/search", query: query)
        async let addons = json("ketvoice-api", "/search", query: query)
        let responses = await [(try? uploaded), (try? addons)].compactMap { $0 }
        if responses.isEmpty { throw APIError.response }
        let decoder = JSONDecoder()
        return responses.flatMap { ($0["tracks"] as? [[String: Any]]) ?? [] }.compactMap { row in
            var item = row
            if item["source"] == nil, let id = item["id"] as? String, id.hasPrefix("kv:upload:") {
                item["source"] = "upload"
            }
            guard let data = try? JSONSerialization.data(withJSONObject: item),
                  let track = try? decoder.decode(Song.self, from: data) else { return nil }
            return track
        }
    }

    func stream(for track: Song) async throws -> URL {
        if track.source == "upload" || track.id.hasPrefix("kv:upload:") {
            let identifier = track.addonId ?? String(track.id.dropFirst("kv:upload:".count))
            let result = try await json("ketvoice-content", "/track", query: [URLQueryItem(name: "id", value: identifier)])
            guard let row = result["track"] as? [String: Any],
                  let address = row["streamURL"] as? String,
                  let url = URL(string: address), url.scheme == "https", url.host == base?.host,
                  url.path.hasPrefix("/storage/v1/object/public/ketvoice-public-media/") else {
                throw APIError.invalidStream
            }
            return url
        }
        guard let source = track.source, let id = track.addonId else { throw APIError.invalidStream }
        let result = try await json("ketvoice-api", "/stream", query: [
            URLQueryItem(name: "source", value: source), URLQueryItem(name: "id", value: id),
            URLQueryItem(name: "title", value: track.title), URLQueryItem(name: "artist", value: track.artist)
        ])
        guard let address = result["url"] as? String, let url = URL(string: address), url.scheme == "https" else {
            throw APIError.invalidStream
        }
        return url
    }

    func lyrics(for track: Song) async throws -> [LyricLine] {
        let response = try await json("ketvoice-content", "/lyrics", query: [
            URLQueryItem(name: "trackId", value: track.id), URLQueryItem(name: "title", value: track.title),
            URLQueryItem(name: "artist", value: track.artist),
            URLQueryItem(name: "durationMs", value: String(track.durationMs))
        ])
        let versions = response["versions"] as? [[String: Any]] ?? []
        return versions.filter { ($0["format"] as? String) == "lrc" }
            .compactMap { $0["content"] as? String }.filter { $0.utf8.count < 1_048_576 }
            .map(LRC.parse).max(by: { $0.count < $1.count }) ?? []
    }
}
