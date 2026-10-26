import AVFoundation
import Combine
import Foundation

@MainActor
final class MusicLibrary: ObservableObject {
    @Published private(set) var state = LibraryState()
    @Published private(set) var downloading = Set<String>()
    @Published var error: String?
    var favorites: [Song] { state.favorites }
    var playlists: [LocalPlaylist] { state.playlists }
    var downloads: [DownloadedSong] { state.downloads }

    private let directory: URL
    private let manifest: URL
    private let api = KetvoiceClient()
    private var jobs: [String: Task<Void, Never>] = [:]
    private var writable = true

    init(directory: URL? = nil) {
        let root = directory ?? URL.applicationSupportDirectory.appending(path: "Ketvoice", directoryHint: .isDirectory)
        self.directory = root
        manifest = root.appending(path: "library.json")
        do {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: manifest.path) {
                state = try JSONDecoder().decode(LibraryState.self, from: Data(contentsOf: manifest))
            } else if let old = UserDefaults.standard.data(forKey: "ketvoice.ios.favorites") {
                state.favorites = try JSONDecoder().decode([Song].self, from: old)
            }
        } catch {
            // Preserve unreadable user data; do not overwrite it with an empty library.
            writable = false
            self.error = "La bibliothèque locale ne peut pas être ouverte : \(error.localizedDescription)"
        }
    }

    private func update(_ mutation: (inout LibraryState) -> Void) throws {
        guard writable else { throw LibraryError.unavailable }
        var next = state
        mutation(&next)
        let data = try JSONEncoder().encode(next)
        try data.write(to: manifest, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        state = next
    }

    func contains(_ song: Song) -> Bool { favorites.contains { $0.id == song.id } }
    func toggle(_ song: Song) {
        do { try update { $0.toggleFavorite(song) } }
        catch { self.error = error.localizedDescription }
    }
    func createPlaylist(_ name: String) {
        let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !trimmed.isEmpty else { return }
        do { try update { $0.playlists.append(LocalPlaylist(name: trimmed)) } }
        catch { self.error = error.localizedDescription }
    }
    func renamePlaylist(_ id: UUID, name: String) {
        let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !trimmed.isEmpty else { return }
        do { try update { state in
            guard let i = state.playlists.firstIndex(where: { $0.id == id }) else { return }
            state.playlists[i].name = trimmed
        } } catch { self.error = error.localizedDescription }
    }
    func deletePlaylist(_ id: UUID) {
        do { try update { $0.playlists.removeAll { $0.id == id } } }
        catch { self.error = error.localizedDescription }
    }
    func add(_ song: Song, to playlist: UUID) {
        do { try update { state in
            guard let i = state.playlists.firstIndex(where: { $0.id == playlist }) else { return }
            state.playlists[i].entries.append(PlaylistEntry(song: song))
        } } catch { self.error = error.localizedDescription }
    }
    func removeEntries(_ ids: Set<UUID>, from playlist: UUID) {
        do { try update { state in
            guard let i = state.playlists.firstIndex(where: { $0.id == playlist }) else { return }
            state.playlists[i].entries.removeAll { ids.contains($0.id) }
        } } catch { self.error = error.localizedDescription }
    }
    func moveEntries(in playlist: UUID, from offsets: IndexSet, to destination: Int) {
        do { try update { state in
            guard let i = state.playlists.firstIndex(where: { $0.id == playlist }) else { return }
            var entries = state.playlists[i].entries
            let selected = offsets.sorted().filter { entries.indices.contains($0) }
            let moving = selected.map { entries[$0] }
            for index in selected.reversed() { entries.remove(at: index) }
            let insertion = max(0, min(entries.count, destination - selected.filter { $0 < destination }.count))
            entries.insert(contentsOf: moving, at: insertion)
            state.playlists[i].entries = entries
        } } catch { self.error = error.localizedDescription }
    }

    func localFile(for song: Song) -> URL? {
        guard let row = downloads.first(where: { $0.id == song.id }),
              row.filename == (row.filename as NSString).lastPathComponent,
              !row.filename.hasPrefix("."), !row.filename.contains("\\") else { return nil }
        let url = directory.appending(path: row.filename)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func download(_ song: Song) {
        guard writable, !downloading.contains(song.id), localFile(for: song) == nil else { return }
        downloading.insert(song.id)
        jobs[song.id] = Task {
            defer { downloading.remove(song.id); jobs[song.id] = nil }
            var destination: URL?
            do {
                let remote = try await api.stream(for: song)
                var request = URLRequest(url: remote)
                request.timeoutInterval = 120
                let (temporary, response) = try await URLSession.shared.download(for: request, delegate: DownloadLimit())
                defer { try? FileManager.default.removeItem(at: temporary) }
                try Task.checkCancellation()
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                      http.url?.scheme == "https" else { throw LibraryError.invalidMedia }
                let attributes = try FileManager.default.attributesOfItem(atPath: temporary.path)
                let bytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
                guard bytes > 0, bytes <= DownloadLimit.maximumBytes else { throw LibraryError.invalidMedia }
                // Do not accept an HTML error page or an arbitrary binary as a completed song.
                let asset = AVURLAsset(url: temporary)
                let audio = try await asset.loadTracks(withMediaType: .audio)
                guard !audio.isEmpty else { throw LibraryError.invalidMedia }
                try Task.checkCancellation()
                let extensionName = ["mp3", "m4a", "aac", "flac", "ogg", "wav", "mp4"].contains(remote.pathExtension.lowercased())
                    ? remote.pathExtension.lowercased() : "audio"
                let filename = UUID().uuidString + "." + extensionName
                let local = directory.appending(path: filename)
                destination = local
                try FileManager.default.moveItem(at: temporary, to: local)
                try update { state in
                    state.downloads.removeAll { $0.id == song.id }
                    state.downloads.append(DownloadedSong(song: song, filename: filename))
                }
                destination = nil
            } catch {
                if let destination { try? FileManager.default.removeItem(at: destination) }
                if !Task.isCancelled { self.error = "Téléchargement impossible : \(error.localizedDescription)" }
            }
        }
    }

    func cancelDownload(_ song: Song) { jobs[song.id]?.cancel() }
    func removeDownload(_ song: Song) {
        guard let file = localFile(for: song) else { return }
        do {
            try update { $0.downloads.removeAll { $0.id == song.id } }
            try FileManager.default.removeItem(at: file)
        } catch { self.error = error.localizedDescription }
    }
}

private enum LibraryError: LocalizedError {
    case unavailable, invalidMedia
    var errorDescription: String? {
        switch self {
        case .unavailable: "La bibliothèque locale est indisponible ; vos données ont été conservées."
        case .invalidMedia: "Fichier audio invalide ou trop volumineux (300 Mo maximum)."
        }
    }
}

private final class DownloadLimit: NSObject, URLSessionDownloadDelegate, Sendable {
    static let maximumBytes: Int64 = 300 * 1024 * 1024
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > Self.maximumBytes || totalBytesExpectedToWrite > Self.maximumBytes {
            downloadTask.cancel()
        }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}
