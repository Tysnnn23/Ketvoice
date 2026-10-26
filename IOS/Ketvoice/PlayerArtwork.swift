import AVFoundation
import AVKit
import SwiftUI

struct PlayerArtwork: View {
    let song: Song
    let isPlaying: Bool
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("reduceMotion") private var reduceMotion = false
    @AppStorage("canvasEnabled") private var canvasEnabled = true
    @State private var canvas: URL?

    private var enabled: Bool { canvasEnabled && !reduceMotion && !systemReduceMotion }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Artwork(song: song, size: min(geometry.size.width, geometry.size.height))
                if let canvas, enabled {
                    LoopingCanvas(url: canvas, playing: isPlaying && scenePhase == .active)
                        .clipShape(RoundedRectangle(cornerRadius: 24))
                        .accessibilityHidden(true)
                }
            }
        }
        .task(id: "\(song.id):\(enabled)") {
            canvas = nil
            guard enabled else { return }
            do {
                let url = try await KetvoiceClient().canvas(for: song)
                try Task.checkCancellation()
                canvas = url
            } catch { /* Missing Canvas is a normal artwork fallback, not a playback failure. */ }
        }
    }
}

private final class CanvasLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

private struct LoopingCanvas: UIViewRepresentable {
    let url: URL
    let playing: Bool

    final class Coordinator {
        let player = AVQueuePlayer()
        var looper: AVPlayerLooper?
        var url: URL?
        init() { player.isMuted = true; player.preventsDisplaySleepDuringVideoPlayback = false }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> CanvasLayerView {
        let view = CanvasLayerView()
        view.playerLayer.player = context.coordinator.player
        view.playerLayer.videoGravity = .resizeAspectFill
        return view
    }
    func updateUIView(_ view: CanvasLayerView, context: Context) {
        let controller = context.coordinator
        if controller.url != url {
            controller.player.pause()
            controller.looper?.disableLooping()
            controller.player.removeAllItems()
            controller.url = url
            controller.looper = AVPlayerLooper(player: controller.player, templateItem: AVPlayerItem(url: url))
        }
        if playing { controller.player.play() } else { controller.player.pause() }
    }
    static func dismantleUIView(_ view: CanvasLayerView, coordinator: Coordinator) {
        coordinator.player.pause()
        coordinator.looper?.disableLooping()
        coordinator.player.removeAllItems()
        view.playerLayer.player = nil
    }
}

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.activeTintColor = .systemPurple
        view.tintColor = .white
        view.prioritizesVideoDevices = false
        return view
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
