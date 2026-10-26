import SwiftUI

struct SongActions: View {
    let song: Song
    @ObservedObject var library: MusicLibrary

    var body: some View {
        Button(library.contains(song) ? "Retirer des favoris" : "Ajouter aux favoris",
               systemImage: library.contains(song) ? "heart.slash" : "heart") { library.toggle(song) }
        Menu("Ajouter à une playlist", systemImage: "text.badge.plus") {
            if library.playlists.isEmpty { Text("Créez une playlist dans la bibliothèque") }
            ForEach(library.playlists) { playlist in
                Button(playlist.name) { library.add(song, to: playlist.id) }
            }
        }
        if library.downloading.contains(song.id) {
            Button("Annuler le téléchargement", systemImage: "xmark.circle") { library.cancelDownload(song) }
        } else if library.localFile(for: song) == nil {
            Button("Télécharger", systemImage: "arrow.down.circle") { library.download(song) }
        }
    }
}

struct PlaylistView: View {
    let id: UUID
    @ObservedObject var library: MusicLibrary
    @EnvironmentObject private var player: MusicPlayer
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var deleting = false
    @State private var name = ""
    private var playlist: LocalPlaylist? { library.playlists.first { $0.id == id } }

    var body: some View {
        Group {
            if let playlist {
                List {
                    Section {
                        Button("Écouter", systemImage: "play.fill") {
                            player.play(playlist.entries.map(\.song), at: 0)
                        }.disabled(playlist.entries.isEmpty)
                    }
                    ForEach(Array(playlist.entries.enumerated()), id: \.element.id) { item in
                        SongRow(song: item.element.song) {
                            player.play(playlist.entries.map(\.song), at: item.offset)
                        }.contextMenu { SongActions(song: item.element.song, library: library) }
                    }
                    .onDelete { offsets in
                        let ids = Set(offsets.compactMap { playlist.entries.indices.contains($0) ? playlist.entries[$0].id : nil })
                        library.removeEntries(ids, from: id)
                    }
                    .onMove { offsets, destination in library.moveEntries(in: id, from: offsets, to: destination) }
                }
                .navigationTitle(playlist.name)
                .toolbar {
                    EditButton()
                    Menu {
                        Button("Renommer") { name = playlist.name; renaming = true }
                        Button("Supprimer", role: .destructive) { deleting = true }
                    } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                }
            } else {
                ContentUnavailableView("Playlist introuvable", systemImage: "music.note.list")
            }
        }
        .scrollContentBackground(.hidden).background(Palette.background)
        .alert("Renommer la playlist", isPresented: $renaming) {
            TextField("Nom", text: $name)
            Button("Enregistrer") { library.renamePlaylist(id, name: name) }
            Button("Annuler", role: .cancel) {}
        }
        .confirmationDialog("Supprimer cette playlist ?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Supprimer", role: .destructive) {
                library.deletePlaylist(id)
                if !library.playlists.contains(where: { $0.id == id }) { dismiss() }
            }
        } message: { Text("Seule la playlist est supprimée. Vos titres et téléchargements sont conservés.") }
    }
}

struct DownloadsView: View {
    @ObservedObject var library: MusicLibrary
    @EnvironmentObject private var player: MusicPlayer
    @State private var removing: Song?

    var body: some View {
        List {
            if !library.downloading.isEmpty {
                Section {
                    HStack { ProgressView(); Text("\(library.downloading.count) téléchargement(s) en cours") }
                }
            }
            if library.downloads.isEmpty {
                ContentUnavailableView("Aucun téléchargement", systemImage: "arrow.down.circle",
                                       description: Text("Ouvrez le menu d'un titre et choisissez Télécharger."))
            }
            ForEach(library.downloads) { row in
                SongRow(song: row.song) {
                    let songs = library.downloads.map(\.song)
                    guard let index = songs.firstIndex(where: { $0.id == row.song.id }) else { return }
                    player.play(songs, at: index)
                }
                .contextMenu {
                    SongActions(song: row.song, library: library)
                    Button("Supprimer le téléchargement", systemImage: "trash", role: .destructive) { removing = row.song }
                }
            }
        }
        .scrollContentBackground(.hidden).background(Palette.background)
        .navigationTitle("Téléchargements")
        .confirmationDialog("Supprimer le téléchargement", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Supprimer", role: .destructive) {
                if let song = removing { library.removeDownload(song) }
                removing = nil
            }
        }
    }
}
