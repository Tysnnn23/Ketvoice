import SwiftUI

@main
struct KetvoiceApp: App {
    @StateObject private var player = MusicPlayer()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(player)
                .preferredColorScheme(.dark)
        }
    }
}
