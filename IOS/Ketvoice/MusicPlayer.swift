@preconcurrency import AVFoundation
import Combine
import MediaPlayer

@MainActor
final class MusicPlayer: ObservableObject {
    @Published private(set) var queue: [Song] = []
    @Published private(set) var index = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var buffering = false
    @Published private(set) var position: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published var shuffle = false
    @Published var repeatMode = RepeatMode.off
    @Published private(set) var sleepDeadline: Date?
    @Published var error: String?
    @Published var showPlayer = false

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }
    private let player = AVPlayer()
    private let library: MusicLibrary
    private let api = KetvoiceClient()
    private var timeObserver: Any?
    private var statusObserver: NSKeyValueObservation?
    private var itemObserver: NSKeyValueObservation?
    private var subscriptions = Set<AnyCancellable>()
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private var generation = 0
    private var wantsPlayback = false
    private var resumeAfterInterruption = false
    private var loadTask: Task<Void, Never>?
    private var sleepTask: Task<Void, Never>?
    private var history: [Int] = []

    init(library: MusicLibrary) {
        self.library = library
        do { try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default) }
        catch { self.error = error.localizedDescription }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                position = time.seconds.isFinite ? max(0, time.seconds) : 0
                let seconds = player.currentItem?.duration.seconds ?? 0
                if seconds.isFinite && seconds > 0 { duration = seconds }
                updateNowPlaying()
            }
        }
        statusObserver = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                isPlaying = player.timeControlStatus == .playing
                buffering = wantsPlayback && (loadTask != nil || player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
                updateNowPlaying()
            }
        }
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)
            .sink { [weak self] note in
                guard let item = note.object as? AVPlayerItem else { return }
                let identity = ObjectIdentifier(item)
                Task { @MainActor [weak self] in
                    guard let self, let currentItem = player.currentItem,
                          ObjectIdentifier(currentItem) == identity else { return }
                    advance(automatic: true)
                }
            }.store(in: &subscriptions)
        NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)
            .sink { [weak self] note in
                let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
                let options = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                Task { @MainActor [weak self] in self?.handleInterruption(type, options: options) }
            }.store(in: &subscriptions)
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .sink { [weak self] note in
                let reason = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
                if reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                    Task { @MainActor [weak self] in self?.pause() }
                }
            }.store(in: &subscriptions)

        let remote = MPRemoteCommandCenter.shared()
        install(remote.playCommand) { $0.resume() }
        install(remote.pauseCommand) { $0.pause() }
        install(remote.togglePlayPauseCommand) { $0.toggle() }
        install(remote.nextTrackCommand) { $0.next() }
        install(remote.previousTrackCommand) { $0.previous() }
        let target = remote.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let seconds = event.positionTime
            Task { @MainActor [weak self] in self?.seek(to: seconds) }
            return .success
        }
        remoteTargets.append((remote.changePlaybackPositionCommand, target))
    }

    private func install(_ command: MPRemoteCommand, action: @escaping @MainActor @Sendable (MusicPlayer) -> Void) {
        let target = command.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in if let self { action(self) } }
            return .success
        }
        remoteTargets.append((command, target))
    }

    /// Called when the application-level player is discarded; it is not tied to a screen lifetime.
    func shutdown() {
        loadTask?.cancel(); sleepTask?.cancel()
        pause()
        if let timeObserver { player.removeTimeObserver(timeObserver); self.timeObserver = nil }
        statusObserver = nil; itemObserver = nil
        subscriptions.removeAll()
        for (command, target) in remoteTargets { command.removeTarget(target) }
        remoteTargets.removeAll()
    }

    func play(_ tracks: [Song], at position: Int) {
        guard tracks.indices.contains(position) else { return }
        queue = tracks; index = position; history = []
        loadCurrent()
    }

    private func loadCurrent() {
        generation += 1
        loadTask?.cancel()
        let request = generation
        wantsPlayback = true
        player.pause(); player.replaceCurrentItem(with: nil); itemObserver = nil
        isPlaying = false; buffering = true; position = 0; error = nil
        duration = Double(current?.durationMs ?? 0) / 1000
        updateNowPlaying()
        guard let track = current else { buffering = false; return }
        loadTask = Task {
            defer { if generation == request { loadTask = nil; buffering = false } }
            do {
                let url: URL
                if let local = library.localFile(for: track) { url = local }
                else { url = try await api.stream(for: track) }
                try Task.checkCancellation()
                guard generation == request else { return }
                let item = AVPlayerItem(url: url)
                itemObserver = item.observe(\.status, options: [.new]) { [weak self] _, _ in
                    Task { @MainActor [weak self] in
                        guard let self, generation == request, player.currentItem?.status == .failed else { return }
                        error = player.currentItem?.error?.localizedDescription ?? "Lecture impossible."
                        pause()
                    }
                }
                player.replaceCurrentItem(with: item)
                if wantsPlayback {
                    try AVAudioSession.sharedInstance().setActive(true)
                    player.play()
                }
            } catch {
                guard generation == request, !Task.isCancelled else { return }
                self.error = error.localizedDescription
                wantsPlayback = false
            }
        }
    }

    func pause() {
        wantsPlayback = false; resumeAfterInterruption = false
        player.pause(); isPlaying = false; buffering = false; updateNowPlaying()
    }
    func resume() {
        wantsPlayback = true
        if player.currentItem?.status == .failed { loadCurrent(); return }
        if player.currentItem != nil {
            do { try AVAudioSession.sharedInstance().setActive(true); player.play() }
            catch { self.error = error.localizedDescription; wantsPlayback = false }
        } else if loadTask == nil { loadCurrent() }
    }
    func toggle() { wantsPlayback ? pause() : resume() }
    func next() { advance(automatic: false) }
    private func advance(automatic: Bool) {
        if automatic && repeatMode == .one { seek(to: 0); resume(); return }
        let next: Int
        if shuffle && queue.count > 1 {
            next = queue.indices.filter { $0 != index }.randomElement() ?? index
        } else if index + 1 < queue.count { next = index + 1 }
        else if repeatMode == .all && !queue.isEmpty { next = 0 }
        else { pause(); return }
        history.append(index)
        index = next
        loadCurrent()
    }
    func previous() {
        if position > 3 { seek(to: 0) }
        else if let previous = history.popLast() { index = previous; loadCurrent() }
        else if index > 0 { index -= 1; loadCurrent() }
        else { seek(to: 0) }
    }
    func seek(to seconds: TimeInterval) {
        guard seconds.isFinite, duration > 0 else { return }
        let bounded = max(0, min(seconds, duration))
        player.seek(to: CMTime(seconds: bounded, preferredTimescale: 600))
        position = bounded
        updateNowPlaying()
    }
    func setSleepTimer(minutes: Int?) {
        sleepTask?.cancel(); sleepTask = nil; sleepDeadline = nil
        guard let minutes, minutes > 0, minutes <= 180 else { return }
        sleepDeadline = Date().addingTimeInterval(Double(minutes * 60))
        sleepTask = Task {
            do { try await Task.sleep(for: .seconds(minutes * 60)); pause(); sleepDeadline = nil }
            catch { /* Replacing or cancelling a timer must never pause playback. */ }
        }
    }

    private func updateNowPlaying() {
        guard let track = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: track.title, MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album, MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
    }
    private func handleInterruption(_ type: UInt?, options: UInt) {
        if type == AVAudioSession.InterruptionType.began.rawValue {
            let wasPlaying = wantsPlayback
            pause()
            resumeAfterInterruption = wasPlaying
        } else if type == AVAudioSession.InterruptionType.ended.rawValue {
            let shouldResume = resumeAfterInterruption && AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume)
            resumeAfterInterruption = false
            if shouldResume { resume() }
        }
    }
}
