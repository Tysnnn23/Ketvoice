import SwiftUI

private enum Palette {
    static let background = Color(red: 0.055, green: 0.045, blue: 0.095)
    static let accent = Color(red: 0.82, green: 0.67, blue: 0.98)
}

@MainActor
final class MusicLibrary: ObservableObject {
    @Published private(set) var favorites: [Song] = []
    private let key = "ketvoice.ios.favorites"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let songs = try? JSONDecoder().decode([Song].self, from: data) { favorites = songs }
    }
    func contains(_ song: Song) -> Bool { favorites.contains { $0.id == song.id } }
    func toggle(_ song: Song) {
        if contains(song) { favorites.removeAll { $0.id == song.id } }
        else { favorites.insert(song, at: 0) }
        UserDefaults.standard.set(try? JSONEncoder().encode(favorites), forKey: key)
    }
}

struct RootView: View {
    @EnvironmentObject private var player: MusicPlayer
    @StateObject private var library = MusicLibrary()
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView(library: library) }
                .tabItem { Label("Accueil", systemImage: "house.fill") }.tag(0)
            NavigationStack { SearchView(library: library) }
                .tabItem { Label("Explorer", systemImage: "magnifyingglass") }.tag(1)
            NavigationStack { LibraryView(library: library) }
                .tabItem { Label("Bibliothèque", systemImage: "square.stack.fill") }.tag(2)
            NavigationStack { SettingsView() }
                .tabItem { Label("Réglages", systemImage: "gearshape.fill") }.tag(3)
        }
        .tint(Palette.accent)
        .safeAreaInset(edge: .bottom) {
            if let song = player.current {
                Button { player.showPlayer = true } label: {
                    HStack(spacing: 12) {
                        Artwork(song: song, size: 44)
                        VStack(alignment: .leading) {
                            Text(song.title).fontWeight(.semibold).lineLimit(1)
                            Text(song.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Button { player.toggle() } label: {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                .frame(width: 44, height: 44)
                        }.accessibilityLabel(player.isPlaying ? "Pause" : "Lecture")
                    }
                    .padding(8)
                    .glassEffect(.regular, in: .rect(cornerRadius: 20))
                    .padding(.horizontal, 12)
                    .padding(.bottom, 4)
                }
                .buttonStyle(.plain)
            }
        }
        .sheet(isPresented: $player.showPlayer) { PlayerView(library: library) }
        .background(Palette.background)
    }
}

private struct Artwork: View {
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

private struct SongRow: View {
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

    var body: some View {
        List {
            Section("Favoris") {
                ForEach(Array(library.favorites.enumerated()), id: \.element.id) { item in
                    let index = item.offset
                    let song = item.element
                    SongRow(song: song) { player.play(library.favorites, at: index) }
                }
                .onDelete { offsets in
                    for index in offsets { library.toggle(library.favorites[index]) }
                }
            }
        }
        .scrollContentBackground(.hidden).background(Palette.background)
        .navigationTitle("Bibliothèque")
    }
}

private struct PlayerView: View {
    @EnvironmentObject private var player: MusicPlayer
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var library: MusicLibrary
    @State private var page = 0
    @State private var lyrics: [LyricLine] = []
    private let api = KetvoiceClient()

    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Button { dismiss() } label: { Image(systemName: "chevron.down").frame(width: 44, height: 44) }
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
                                            .foregroundStyle(line.time <= player.position ? .white : .secondary)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .id(line.id)
                                    }
                                }.padding()
                            }
                            .onChange(of: player.position) { _, time in
                                if let active = lyrics.last(where: { $0.time <= time }) {
                                    withAnimation(.easeInOut(duration: 0.3)) { proxy.scrollTo(active.id, anchor: .center) }
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
                            Artwork(song: song, size: min(UIScreen.main.bounds.width - 60, 340))
                            Text(song.title).font(.title.bold()).lineLimit(2)
                            Text(song.artist).foregroundStyle(.secondary)
                            Spacer(minLength: 0)
                        }
                    }
                }.frame(maxHeight: .infinity)
                if let error = player.error { Text(error).font(.caption).foregroundStyle(.red) }
                Slider(value: Binding(get: { player.position }, set: { player.seek(to: $0) }), in: 0...max(player.duration, 1))
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

private struct SettingsView: View {
    @AppStorage("reduceMotion") private var reduceMotion = false
    @AppStorage("automix") private var automix = false

    var body: some View {
        Form {
            Section("Interface") { Toggle("Réduire les animations", isOn: $reduceMotion) }
            Section("Lecture") {
                Toggle("AutoMix (BETA)", isOn: $automix).disabled(true)
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
