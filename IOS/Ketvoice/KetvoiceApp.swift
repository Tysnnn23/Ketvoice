import SwiftUI

@main
struct KetvoiceApp: App {
    @StateObject private var library: MusicLibrary
    @StateObject private var player: MusicPlayer

    init() {
        let library = MusicLibrary()
        _library = StateObject(wrappedValue: library)
        _player = StateObject(wrappedValue: MusicPlayer(library: library))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(player)
                .preferredColorScheme(.dark)
        }
    }
}
