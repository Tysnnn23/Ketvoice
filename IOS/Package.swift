// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KetvoiceCore",
    platforms: [.macOS(.v13), .iOS("26.0")],
    products: [.library(name: "KetvoiceCore", targets: ["KetvoiceCore"])],
    targets: [
        .target(name: "KetvoiceCore", path: "Ketvoice", exclude: [
            "KetvoiceApp.swift", "KetvoiceClient.swift", "MusicPlayer.swift", "MusicLibrary.swift",
            "RootView.swift", "LibraryViews.swift", "PlayerArtwork.swift", "Info.plist"
        ], sources: ["Models.swift"]),
        .testTarget(name: "KetvoiceCoreTests", dependencies: ["KetvoiceCore"], path: "Tests")
    ]
)
