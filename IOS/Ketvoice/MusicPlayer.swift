import AVFoundation
import Combine
import MediaPlayer

@MainActor
final class MusicPlayer: ObservableObject {
    @Published private(set) var queue: [Song] = []
    @Published private(set) var index = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var position: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published var error: String?
    @Published var showPlayer = false

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }
    private let player = AVPlayer()
    private let api = KetvoiceClient()
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var generation = 0

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                self?.position = time.seconds.isFinite ? time.seconds : 0
                let seconds = self?.player.currentItem?.duration.seconds ?? 0
                self?.duration = seconds.isFinite ? seconds : 0
            }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] note in
            Task { @MainActor [weak self] in
                guard let self, note.object as? AVPlayerItem === self.player.currentItem else { return }
                self.next()
            }
        }
        let remote = MPRemoteCommandCenter.shared()
        remote.playCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.resume() }
            return .success
        }
        remote.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.pause() }
            return .success
        }
        remote.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.next() }
            return .success
        }
        remote.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.previous() }
            return .success
        }
    }

    func play(_ tracks: [Song], at position: Int) {
        guard tracks.indices.contains(position) else { return }
        queue = tracks
        index = position
        loadCurrent()
    }

    private func loadCurrent() {
        generation += 1
        let request = generation
        player.pause()
        player.replaceCurrentItem(with: nil)
        isPlaying = false
        position = 0
        error = nil
        guard let track = current else { return }
        Task {
            do {
                let url = try await api.stream(for: track)
                guard generation == request else { return }
                try AVAudioSession.sharedInstance().setActive(true)
                player.replaceCurrentItem(with: AVPlayerItem(url: url))
                player.play()
                isPlaying = true
                MPNowPlayingInfoCenter.default().nowPlayingInfo = [
                    MPMediaItemPropertyTitle: track.title,
                    MPMediaItemPropertyArtist: track.artist,
                    MPMediaItemPropertyAlbumTitle: track.album
                ]
            } catch {
                guard generation == request else { return }
                self.error = error.localizedDescription
            }
        }
    }

    func pause() { player.pause(); isPlaying = false }
    func resume() { if player.currentItem != nil { player.play(); isPlaying = true } else { loadCurrent() } }
    func toggle() { isPlaying ? pause() : resume() }
    func next() {
        guard index + 1 < queue.count else { pause(); return }
        index += 1
        loadCurrent()
    }
    func previous() {
        if position > 3 { seek(to: 0) }
        else if index > 0 { index -= 1; loadCurrent() }
        else { seek(to: 0) }
    }
    func seek(to seconds: TimeInterval) {
        guard seconds.isFinite else { return }
        player.seek(to: CMTime(seconds: max(0, min(seconds, duration)), preferredTimescale: 600))
    }
}
