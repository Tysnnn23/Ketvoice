import Foundation
import XCTest
@testable import KetvoiceCore

final class ModelsTests: XCTestCase {
    private func song(_ id: String = "kv:test:one") -> Song {
        Song(id: id, title: "Titre", artist: "Artiste")
    }

    func testDecodesMinimalCatalogRecord() throws {
        let bytes = Data(#"{"id":"kv:um:1","title":"Test","artist":"Artist"}"#.utf8)
        let track = try JSONDecoder().decode(Song.self, from: bytes)
        XCTAssertEqual(track.album, "")
        XCTAssertEqual(track.durationMs, 0)
        XCTAssertNil(track.streamURL)
    }

    func testMissingTrackIdentityIsRejected() {
        let bytes = Data(#"{"title":"Test","artist":"Artist"}"#.utf8)
        XCTAssertThrowsError(try JSONDecoder().decode(Song.self, from: bytes))
    }

    func testFavoriteIdentityDoesNotDependOnChangedMetadata() {
        var state = LibraryState()
        state.toggleFavorite(song())
        state.toggleFavorite(Song(id: song().id, title: "Titre corrigé", artist: "Autre"))
        XCTAssertTrue(state.favorites.isEmpty)
    }

    func testDuplicateSongsHaveDistinctPlaylistEntryIdentities() {
        let a = PlaylistEntry(song: song())
        let b = PlaylistEntry(song: song())
        XCTAssertNotEqual(a.id, b.id)
        XCTAssertEqual(a.song.id, b.song.id)
    }

    func testLibraryRoundTripPreservesOrderAndEntryIdentities() throws {
        let playlist = LocalPlaylist(name: "Mes titres", entries: [PlaylistEntry(song: song("a")), PlaylistEntry(song: song("b"))])
        let state = LibraryState(favorites: [song()], playlists: [playlist])
        let copy = try JSONDecoder().decode(LibraryState.self, from: JSONEncoder().encode(state))
        XCTAssertEqual(copy.playlists, [playlist])
        XCTAssertEqual(copy.favorites, state.favorites)
    }

    func testLRCMultipleTimestampsAndFractions() {
        let rows = LRC.parse("[00:03.1][00:04.12] Bonjour\n[00:01.123] Début")
        XCTAssertEqual(rows.map(\.time), [1.123, 3.1, 4.12])
        XCTAssertEqual(rows.map(\.text), ["Début", "Bonjour", "Bonjour"])
    }

    func testLRCOffsetAndDuplicateTimestamps() {
        let rows = LRC.parse("[offset:-500]\n[00:00.20] Premier\n[00:01] A\n[00:01] Traduction")
        XCTAssertEqual(rows.map(\.time), [0, 0.5])
        XCTAssertEqual(rows.last?.text, "A\nTraduction")
    }

    func testLRCInvalidSecondsAndMetadataDoNotBecomeLyrics() {
        XCTAssertTrue(LRC.parse("[ar:Artiste]\n[00:99.2] invalide\n[00:10.0]  ").isEmpty)
    }
}
