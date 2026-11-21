package com.ketvoice.desktop.ui

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Fictional catalogue for the desktop UI tests: no network, covers drawn here. */
object Fixtures {
    private data class A(val title: String, val artist: String, val year: String, val type: String, val colors: IntArray, val titles: List<String>)

    private val albums = listOf(
        A("Marée basse", "Nora Vale", "2025", "Album", intArrayOf(0xFF1F3A5F.toInt(), 0xFFE8B04A.toInt(), 0xFFF4EBD9.toInt()),
            listOf("Le large", "Sel", "Phare éteint", "Nuit claire", "Sous la jetée", "Corail", "Dernier ferry", "Écume")),
        A("Néons calmes", "Kadi", "2024", "Album", intArrayOf(0xFF5B2A86.toInt(), 0xFFFF7A9C.toInt(), 0xFF1A1033.toInt()),
            listOf("Minuit vingt", "Taxi", "Velours", "Rue des Lilas", "Boucle", "Satellite")),
        A("Terre ocre", "The Quiet Coast", "2023", "Album", intArrayOf(0xFFB4532A.toInt(), 0xFF2E2A24.toInt(), 0xFFEBD3B0.toInt()),
            listOf("Dust", "Wide Open", "Canyon Song", "Low Sun", "Paper Birds", "Driftwood", "Home Again")),
        A("Verre", "Lune Rouge", "2025", "Album", intArrayOf(0xFF2F6B4F.toInt(), 0xFFD7E3D0.toInt(), 0xFF0E1F17.toInt()),
            listOf("Transparence", "Fragile", "Prisme", "Reflet", "Éclat")),
        A("Été", "Nora Vale", "2026", "Single", intArrayOf(0xFFF2C14E.toInt(), 0xFFF78154.toInt(), 0xFF4D9078.toInt()), listOf("Été")),
        A("Coton", "Kadi", "2026", "EP", intArrayOf(0xFFE9E4F0.toInt(), 0xFF8E7DBE.toInt(), 0xFF3B3355.toInt()), listOf("Coton", "Nuage", "Plume")),
    )

    val refs: List<AlbumRef> = albums.mapIndexed { i, a -> AlbumRef("fx-album-$i", a.title, a.artist, "fixture:cover:$i", a.year, a.type) }
    val tracks: List<Track> = albums.flatMapIndexed { i, a ->
        a.titles.mapIndexed { n, t ->
            Track(id = "fx-$i-$n", title = t, artist = if (n == 2 && i == 0) "Nora Vale, Kadi" else a.artist, album = a.title,
                durationMs = 150_000L + ((i * 7 + n * 13) % 9) * 17_000L, coverUrl = "fixture:cover:$i", albumId = "fx-album-$i",
                explicit = i == 1 && n == 1)
        }
    }
    val artists = listOf("Nora Vale", "Kadi", "The Quiet Coast", "Lune Rouge").mapIndexed { i, n -> ArtistRef("fx-artist-$i", n, "fixture:artist:$i") }

    fun albumTracks(i: Int) = tracks.filter { it.albumId == "fx-album-$i" }

    /** Flat compositions of three colours, as real covers often are (so CoverColors finds a page and an accent). */
    fun installCovers() {
        albums.forEachIndexed { i, a -> Covers.put("fixture:cover:$i", draw(320, 320, a.colors, i)) }
        artists.forEachIndexed { i, _ -> Covers.put("fixture:artist:$i", draw(1200, 520, albums[i % 4].colors, i + 3)) }
    }

    private fun draw(w: Int, h: Int, c: IntArray, seed: Int) = Surface.makeRasterN32Premul(w, h).run {
        canvas.clear(c[0])
        val p = Paint().apply { isAntiAlias = true }
        p.color = c[1]
        when (seed % 3) {
            0 -> canvas.drawCircle(w * .62f, h * .42f, minOf(w, h) * .26f, p)
            1 -> canvas.drawRect(Rect.makeXYWH(0f, h * .62f, w.toFloat(), h * .38f), p)
            else -> canvas.drawRRect(org.jetbrains.skia.RRect.makeXYWH(w * .18f, h * .18f, w * .5f, h * .5f, w * .06f), p)
        }
        p.color = c[2]
        canvas.drawRect(Rect.makeXYWH(w * .08f, h * .84f, w * .4f, h * .05f), p)
        makeImageSnapshot().toComposeImageBitmap()
    }

    fun library(): MemoryLibrary = MemoryLibrary().apply {
        seed(
            favourites = tracks.filterIndexed { i, _ -> i % 3 == 0 },
            playlists = listOf(UserPlaylist("pl-road", "Route du soir", tracks.take(6)), UserPlaylist("pl-focus", "Concentration", tracks.drop(14).take(5))),
            downloads = listOf(DownloadItem(tracks[0], 1f), DownloadItem(tracks[1], 1f), DownloadItem(tracks[9], .62f), DownloadItem(tracks[15], .18f)),
            history = listOf(tracks[8], tracks[20], tracks[3], tracks[14], tracks[27], tracks[11]),
        )
    }
}

object FixtureCatalog : CatalogSource {
    override suspend fun home() = HomeData(
        forYou = listOf(0, 8, 14, 21, 26, 3, 10, 17).map { Fixtures.tracks[it] },
        recent = emptyList(),
        shelves = listOf(Shelf("Mix du jour", "D'après vos écoutes", Fixtures.tracks.shuffled(kotlin.random.Random(4)).take(10)),
            Shelf("Nouveautés", albums = Fixtures.refs)),
    )
    override suspend fun search(query: String): SearchResults {
        val q = query.lowercase()
        return SearchResults(
            Fixtures.tracks.filter { q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() },
            Fixtures.refs.filter { q in it.title.lowercase() || q in it.artist.lowercase() },
            Fixtures.artists.filter { q in it.name.lowercase() })
    }
    override suspend fun album(ref: AlbumRef): AlbumData? {
        val i = Fixtures.refs.indexOfFirst { it.title == ref.title }.takeIf { it >= 0 } ?: return null
        return AlbumData(Fixtures.refs[i], Fixtures.albumTracks(i), Fixtures.tracks.filterNot { it.albumId == "fx-album-$i" }.take(9))
    }
    override suspend fun artist(ref: ArtistRef): ArtistData? {
        val a = Fixtures.artists.firstOrNull { it.name == ref.name } ?: return null
        val mine = Fixtures.refs.filter { it.artist == a.name }
        return ArtistData(a, Fixtures.tracks.filter { it.artist.startsWith(a.name) }, mine.filter { it.type == "Album" }, mine.filter { it.type != "Album" })
    }
    override suspend fun categories() = listOf("Pop", "Hip-hop", "Électro", "Rock", "Jazz", "Chill", "R&B", "Classique")
        .mapIndexed { i, t -> Category("c$i", t, "fixture:cover:${i % 6}") }
    override suspend fun category(category: Category) = Fixtures.tracks.take(12)
}

/** A player that records the facade calls and keeps a real queue. */
class RecordingPlayer : PlayerControls {
    val calls = mutableListOf<String>()
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state
    override val volume = MutableStateFlow(.7f)
    override val loading = MutableStateFlow(false)
    override val format = MutableStateFlow<AudioFormatInfo?>(null)
    private fun ids(q: List<Track>) = q.indices.map { "e$it-${q[it].id}" }

    override fun playQueue(tracks: List<Track>, index: Int) { calls += "playQueue:${tracks[index].id}"; _state.value = _state.value.copy(queue = tracks, queueEntryIds = ids(tracks), index = index, isPlaying = true, durationMs = tracks[index].durationMs) }
    override fun toggle() { calls += "toggle"; _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying) }
    override fun next() { calls += "next"; _state.value.let { s -> if (s.index < s.queue.lastIndex) _state.value = s.copy(index = s.index + 1) } }
    override fun previous() { calls += "previous"; _state.value.let { s -> if (s.index > 0) _state.value = s.copy(index = s.index - 1) } }
    override fun seekTo(ms: Long) { calls += "seekTo"; _state.value = _state.value.copy(positionMs = ms) }
    override fun setVolume(v: Float) { calls += "volume"; volume.value = v }
    override fun enqueue(track: Track) { calls += "enqueue:${track.id}"; _state.value.let { s -> val q = s.queue + track; _state.value = s.copy(queue = q, queueEntryIds = ids(q)) } }
    override fun playNext(track: Track) { calls += "playNext:${track.id}"; _state.value.let { s -> val q = s.queue.toMutableList().apply { add(s.index + 1, track) }; _state.value = s.copy(queue = q, queueEntryIds = ids(q)) } }
    override fun removeAt(index: Int) { calls += "removeAt:$index" }
    override fun moveQueue(from: Int, to: Int) {
        calls += "move:$from->$to"
        _state.value.let { s -> val q = s.queue.toMutableList(); val e = s.queueEntryIds.toMutableList(); q.add(to, q.removeAt(from)); e.add(to, e.removeAt(from)); _state.value = s.copy(queue = q, queueEntryIds = e) }
    }
    override fun setShuffle(on: Boolean) { calls += "shuffle:$on"; _state.value = _state.value.copy(shuffle = on) }
    override fun setRepeat(one: Boolean, all: Boolean) { calls += "repeat:$one,$all"; _state.value = _state.value.copy(repeatOne = one, repeatAll = all) }

    /** A queue on its fourth title, a minute in, lossless. */
    fun playing(): RecordingPlayer = apply {
        val q = Fixtures.albumTracks(0)
        _state.value = PlayerUiState(q, ids(q), 3, isPlaying = true, positionMs = 62_000, durationMs = q[3].durationMs, repeatAll = true)
        format.value = AudioFormatInfo("flac", 44_100, 16, 2, 0)
        calls.clear()
    }
}
