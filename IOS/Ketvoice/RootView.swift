import SwiftUI

enum Palette {
    static let background = Color(red: 0.055, green: 0.045, blue: 0.095)
    static let accent = Color(red: 0.82, green: 0.67, blue: 0.98)
}

struct RootView: View {
    @EnvironmentObject private var player: MusicPlayer
    @EnvironmentObject private var library: MusicLibrary
    @State private var tab = 0
    @AppStorage("language") private var language = "fr"

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView(library: library).toolbar { settingsLink } }
                .tabItem { Label("Accueil", systemImage: "house.fill") }.tag(0)
            NavigationStack { SearchView(library: library).toolbar { settingsLink } }
                .tabItem { Label("Explorer", systemImage: "magnifyingglass") }.tag(1)
            NavigationStack { LibraryView(library: library).toolbar { settingsLink } }
                .tabItem { Label("Bibliothèque", systemImage: "square.stack.fill") }.tag(2)
        }
        .tint(Palette.accent)
        .safeAreaInset(edge: .bottom) {
            if let song = player.current {
                HStack(spacing: 12) {
                    Button { player.showPlayer = true } label: {
                        HStack(spacing: 12) {
                            Artwork(song: song, size: 44)
                            VStack(alignment: .leading) {
                                Text(song.title).fontWeight(.semibold).lineLimit(1)
                                Text(song.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    Button { player.toggle() } label: {
                        if player.buffering { ProgressView().frame(width: 44, height: 44) }
                        else { Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").frame(width: 44, height: 44) }
                    }.accessibilityLabel(player.isPlaying ? "Pause" : "Lecture")
                }
                .padding(8)
                .glassEffect(.regular, in: .rect(cornerRadius: 20))
                .padding(.horizontal, 12).padding(.bottom, 4)
                .buttonStyle(.plain)
            }
        }
        .fullScreenCover(isPresented: $player.showPlayer) { PlayerView(library: library) }
        .alert("Bibliothèque", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) {
            Button("OK") { library.error = nil }
        } message: { Text(library.error ?? "") }
        .background(Palette.background)
        .environment(\.locale, Locale(identifier: language))
    }

    private var settingsLink: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            NavigationLink { SettingsView() } label: { Image(systemName: "gearshape").frame(width: 44, height: 44) }
                .accessibilityLabel("Réglages")
        }
    }
}

struct Artwork: View {
    let song: Song
    let size: CGFloat

    var body: some View {
        AsyncImage(url: song.artworkURL.flatMap(URL.init(string:))) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            ZStack {
                Palette.accent.opacity(0.18)
                Image(systemName: "music.note").foregroundStyle(Palette.accent)
            }
        }
        .frame(width: size, height: size).clipped()
        .clipShape(RoundedRectangle(cornerRadius: size / 7))
        .accessibilityHidden(true)
    }
}

struct SongRow: View {
    let song: Song
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Artwork(song: song, size: 54)
                VStack(alignment: .leading, spacing: 4) {
                    Text(song.title).font(.body.weight(.medium)).lineLimit(1)
                    Text(song.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Image(systemName: "play.fill").font(.caption).foregroundStyle(Palette.accent)
            }.padding(.vertical, 3)
        }.buttonStyle(.plain)
    }
}

private struct HomeView: View {
    @EnvironmentObject private var player: MusicPlayer
    @ObservedObject var library: MusicLibrary

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("La musique, à votre rythme.").font(.largeTitle.bold())
                Text("Retrouvez vos titres enregistrés et explorez le catalogue Ketvoice.")
                    .foregroundStyle(.secondary)
                Text("Favoris").font(.title2.bold())
                if library.favorites.isEmpty {
                    ContentUnavailableView("Pas encore de favoris", systemImage: "heart",
                                           description: Text("Recherchez un titre et ajoutez-le à vos favoris."))
                }
                ForEach(library.favorites) { song in
                    SongRow(song: song) {
                        guard let index = library.favorites.firstIndex(where: { $0.id == song.id }) else { return }
                        player.play(library.favorites, at: index)
                    }
                    .contextMenu { SongActions(song: song, library: library) }
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
        }
        .background(Palette.background)
        .navigationTitle("Ketvoice")
    }
}

private struct SearchView: View {
    @EnvironmentObject private var player: MusicPlayer
    @ObservedObject var library: MusicLibrary
    @State private var query = ""
    @State private var tracks: [Song] = []
    @State private var error: String?
    @State private var loading = false
    private let api = KetvoiceClient()

    var body: some View {
        List {
            if loading { ProgressView("Recherche…") }
            if let error { Text(error).foregroundStyle(.secondary) }
            ForEach(Array(tracks.enumerated()), id: \.element.id) { item in
                let index = item.offset
                let song = item.element
                HStack {
                    SongRow(song: song) { player.play(tracks, at: index) }
                        .contextMenu { SongActions(song: song, library: library) }
                    Button { library.toggle(song) } label: {
                        Image(systemName: library.contains(song) ? "heart.fill" : "heart")
                            .frame(width: 44, height: 44)
                    }.buttonStyle(.plain).accessibilityLabel("Favoris")
                }
                .listRowBackground(Color.white.opacity(0.04))
            }
        }
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .navigationTitle("Explorer")
        .searchable(text: $query, prompt: "Titre, artiste ou album")
        .task(id: query) {
            let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard term.count >= 2 else { tracks = []; error = nil; loading = false; return }
            loading = true
            do {
                try await Task.sleep(for: .milliseconds(250))
                let found = try await api.search(term)
                try Task.checkCancellation()
                var seen = Set<String>()
                tracks = found.filter { seen.insert($0.id).inserted }
                error = nil
            } catch is CancellationError { return }
            catch { tracks = []; self.error = error.localizedDescription }
            loading = false
        }
    }
}

private struct LibraryView: View {
    @EnvironmentObject private var player: MusicPlayer
    @ObservedObject var library: MusicLibrary
    @State private var creating = false
    @State private var playlistName = ""

    var body: some View {
        List {
            Section {
                NavigationLink { DownloadsView(library: library) } label: {
                    Label("Téléchargements", systemImage: "arrow.down.circle")
                }
            }
            Section("Playlists") {
                Button { playlistName = ""; creating = true } label: { Label("Créer une playlist", systemImage: "plus") }
                ForEach(library.playlists) { playlist in
                    NavigationLink { PlaylistView(id: playlist.id, library: library) } label: {
                        VStack(alignment: .leading) {
                            Text(playlist.name)
                            Text("\(playlist.entries.count) titres").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Section("Favoris") {
                ForEach(Array(library.favorites.enumerated()), id: \.element.id) { item in
                    let index = item.offset
                    let song = item.element
                    SongRow(song: song) { player.play(library.favorites, at: index) }
                        .contextMenu { SongActions(song: song, library: library) }
                }
                .onDelete { offsets in
                    let songs = offsets.compactMap { library.favorites.indices.contains($0) ? library.favorites[$0] : nil }
                    for song in songs { library.toggle(song) }
                }
            }
        }
        .scrollContentBackground(.hidden).background(Palette.background)
        .navigationTitle("Bibliothèque")
        .alert("Créer une playlist", isPresented: $creating) {
            TextField("Nom", text: $playlistName)
            Button("Créer") { library.createPlaylist(playlistName) }
            Button("Annuler", role: .cancel) {}
        }
    }
}

private struct PlayerView: View {
    @EnvironmentObject private var player: MusicPlayer
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var library: MusicLibrary
    @State private var page = 0
    @State private var lyrics: [LyricLine] = []
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @AppStorage("reduceMotion") private var reduceMotion = false
    private let api = KetvoiceClient()

    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Button {
                    if page != 0 { page = 0 } else { dismiss() }
                } label: { Image(systemName: "chevron.down").frame(width: 44, height: 44) }
                    .accessibilityLabel("Retour")
                Spacer()
                Text("KETVOICE").font(.caption.weight(.bold)).tracking(3)
                Spacer()
                if let song = player.current {
                    Button { library.toggle(song) } label: {
                        Image(systemName: library.contains(song) ? "heart.fill" : "heart")
                            .frame(width: 44, height: 44)
                    }
                }
            }
            Picker("Affichage", selection: $page) {
                Text("Lecture").tag(0); Text("Paroles").tag(1); Text("File").tag(2)
            }.pickerStyle(.segmented)
            if let song = player.current {
                Group {
                    switch page {
                    case 1:
                        ScrollViewReader { proxy in
                            ScrollView {
                                VStack(spacing: 24) {
                                    if lyrics.isEmpty { Text("Paroles indisponibles").foregroundStyle(.secondary) }
                                    ForEach(lyrics) { line in
                                        Text(line.text).font(.title3.weight(line.time <= player.position ? .bold : .regular))
                                            .foregroundStyle(line.time <= player.position ? Color.white : Color.secondary)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .id(line.id)
                                            .onTapGesture { player.seek(to: line.time) }
                                    }
                                }.padding()
                            }
                            .onChange(of: player.position) { _, time in
                                if let active = lyrics.last(where: { $0.time <= time }) {
                                    withAnimation(reduceMotion || systemReduceMotion ? nil : .easeInOut(duration: 0.3)) {
                                        proxy.scrollTo(active.id, anchor: .center)
                                    }
                                }
                            }
                        }
                    case 2:
                        List(Array(player.queue.enumerated()), id: \.offset) { item in
                            let index = item.offset
                            let entry = item.element
                            Button { player.play(player.queue, at: index) } label: {
                                HStack { Text(entry.title); Spacer(); if index == player.index { Image(systemName: "waveform") } }
                            }
                        }.scrollContentBackground(.hidden)
                    default:
                        VStack(spacing: 20) {
                            Spacer(minLength: 0)
                            GeometryReader { geometry in
                                PlayerArtwork(song: song, isPlaying: player.isPlaying)
                                    .frame(width: min(geometry.size.width, geometry.size.height),
                                           height: min(geometry.size.width, geometry.size.height))
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                            }.aspectRatio(1, contentMode: .fit)
                            Text(song.title).font(.title.bold()).lineLimit(2)
                            Text(song.artist).foregroundStyle(.secondary)
                            Spacer(minLength: 0)
                        }
                    }
                }.frame(maxHeight: .infinity)
                if let error = player.error { Text(error).font(.caption).foregroundStyle(.red) }
                if player.buffering { ProgressView("Chargement…") }
                Slider(value: Binding(get: { min(player.position, max(player.duration, 1)) }, set: { player.seek(to: $0) }), in: 0...max(player.duration, 1))
                    .accessibilityLabel("Position de lecture")
                HStack {
                    Text(timeLabel(player.position))
                    Spacer()
                    Text(timeLabel(player.duration))
                }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                HStack(spacing: 44) {
                    Button { player.previous() } label: { Image(systemName: "backward.end.fill") }
                    Button { player.toggle() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title).frame(width: 68, height: 68)
                            .glassEffect(.regular, in: .circle)
                    }
                    Button { player.next() } label: { Image(systemName: "forward.end.fill") }
                }.font(.title2).buttonStyle(.plain)
                HStack(spacing: 24) {
                    Button { player.shuffle.toggle() } label: {
                        Image(systemName: "shuffle").foregroundStyle(player.shuffle ? Palette.accent : .secondary)
                    }.accessibilityLabel("Lecture aléatoire")
                    Button {
                        player.repeatMode = player.repeatMode == .off ? .all : player.repeatMode == .all ? .one : .off
                    } label: {
                        Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                            .foregroundStyle(player.repeatMode != .off ? Palette.accent : .secondary)
                    }.accessibilityLabel("Répétition : \(player.repeatMode.rawValue)")
                    AirPlayButton().frame(width: 44, height: 44).accessibilityLabel("Sortie audio AirPlay")
                    Menu {
                        ForEach([15, 30, 45, 60], id: \.self) { minutes in
                            Button("\(minutes) min") { player.setSleepTimer(minutes: minutes) }
                        }
                        Button("Annuler", role: .cancel) { player.setSleepTimer(minutes: nil) }
                    } label: {
                        Image(systemName: "moon.zzz").foregroundStyle(player.sleepDeadline == nil ? .secondary : Palette.accent)
                    }.accessibilityLabel("Minuterie de sommeil")
                }.buttonStyle(.plain).frame(minHeight: 44)
                if let deadline = player.sleepDeadline {
                    Text("Arrêt à \(deadline, style: .time)").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(20).padding(.bottom, 14)
        .background(Palette.background.gradient)
        .tint(Palette.accent)
        .task(id: player.current?.id) {
            lyrics = []
            guard let song = player.current else { return }
            lyrics = (try? await api.lyrics(for: song)) ?? []
        }
    }

    private func timeLabel(_ time: TimeInterval) -> String {
        let seconds = Int(time.isFinite ? max(0, time) : 0)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

struct SettingsView: View {
    @AppStorage("reduceMotion") private var reduceMotion = false
    @AppStorage("canvasEnabled") private var canvasEnabled = true
    @AppStorage("language") private var language = "fr"

    var body: some View {
        Form {
            Section("Interface") {
                Picker("Langue de l'application", selection: $language) {
                    ForEach(["fr", "en", "hi", "ja", "zh", "ru", "it", "es", "de", "pt", "ko"], id: \.self) { code in
                        Text(Locale(identifier: code).localizedString(forLanguageCode: code) ?? code).tag(code)
                    }
                }
                Toggle("Réduire les animations", isOn: $reduceMotion)
                Toggle("Spotify Canvas", isOn: $canvasEnabled)
                Text("Canvas reste muet et s'arrête avec la musique. La réduction des animations désactive la vidéo.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Lecture") {
                Text("AutoMix : portage en cours")
                Text("La transition à deux flux nécessite encore un portage audio iOS ; ce réglage ne modifie pas la lecture.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Synchronisation") {
                Text("Le catalogue est partagé via Ketvoice API. Favoris et réglages restent sur cet iPhone : ils ne sont pas encore synchronisés avec Android.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .scrollContentBackground(.hidden).background(Palette.background)
        .navigationTitle("Réglages")
    }
}
