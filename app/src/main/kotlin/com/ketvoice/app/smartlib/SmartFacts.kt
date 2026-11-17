package com.ketvoice.app.smartlib

import android.content.Context
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.MusicalKey
import com.ketvoice.app.data.ProviderConfig
import com.ketvoice.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the facts of titles come from on the device: the catalogue's albums (styles, year), kept in a
 * small file so that each album is asked once; the catalogue's style charts; Ketmix's measurements.
 */
object SmartFacts {
    private const val LIMIT = 3000
    private val albums = ConcurrentHashMap<String, AlbumFacts>()
    private val charts = ConcurrentHashMap<String, Genre>()
    private var file: File? = null
    private var context: Context? = null

    @Synchronized fun init(context: Context) {
        if (file != null) return
        this.context = context.applicationContext
        val target = File(context.filesDir, "smartlib-albums.json")
        file = target
        runCatching { if (target.isFile && target.length() < 1_000_000L) albums.putAll(decode(target.readText())) }
    }

    /** Titles of a catalogue style chart are of that style. */
    fun noteChart(genre: Genre, tracks: List<Track>) { tracks.forEach { charts[it.id] = genre } }

    internal fun album(id: String?): AlbumFacts? = id?.let { albums[it] }

    internal fun resetForTests() { albums.clear(); charts.clear(); file = null; context = null }
    internal fun putAlbumForTests(id: String, facts: AlbumFacts) { albums[id] = facts }

    /** Asks the catalogue for the albums of [tracks] it was never asked about, [budget] at most. */
    suspend fun learnAlbums(tracks: List<Track>, budget: Int = 24): Int = withContext(Dispatchers.IO) {
        val ids = tracks.asSequence().filter { it.addon == "um" }.mapNotNull { it.albumId }
            .filter { it.isNotBlank() && it.all(Char::isDigit) && !albums.containsKey(it) }.distinct().take(budget).toList()
        var learned = 0
        for (id in ids) {
            val facts = fetchAlbum(id) ?: continue
            albums[id] = facts; learned++
        }
        if (learned > 0) save()
        learned
    }

    private fun fetchAlbum(id: String): AlbumFacts? {
        val address = ProviderConfig.publicUrl("https://api.deezer.com/album/$id") ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000; connection.readTimeout = 8000
            if (connection.responseCode !in 200..299) return null
            parseAlbum(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { null } finally { connection?.disconnect() }
    }

    /** Styles and year of a catalogue album; an album without them is remembered as empty, so it is not asked again. */
    internal fun parseAlbum(json: String): AlbumFacts? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.has("error")) return null
        val ids = mutableSetOf(root.optInt("genre_id", 0))
        root.optJSONObject("genres")?.optJSONArray("data")?.let { data ->
            for (i in 0 until data.length()) data.optJSONObject(i)?.optInt("id")?.let(ids::add)
        }
        return AlbumFacts(ids.mapNotNull(Genre::ofCatalogue).toSet(), Facts.yearOf(root.optString("release_date")))
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            val text = encode(albums.entries.toList().takeLast(LIMIT).associate { it.key to it.value })
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(target)) { target.writeText(text); temporary.delete() }
        }
    }

    internal fun encode(rows: Map<String, AlbumFacts>): String {
        val root = JSONObject()
        rows.forEach { (id, facts) ->
            root.put(id, JSONObject().put("g", org.json.JSONArray(facts.genres.map { it.name })).put("y", facts.year ?: 0))
        }
        return root.toString()
    }

    internal fun decode(text: String): Map<String, AlbumFacts> {
        val root = JSONObject(text)
        val out = HashMap<String, AlbumFacts>()
        for (id in root.keys()) {
            val row = root.optJSONObject(id) ?: continue
            val names = row.optJSONArray("g")
            val genres = (0 until (names?.length() ?: 0)).mapNotNull { i -> Genre.entries.firstOrNull { it.name == names?.optString(i) } }.toSet()
            out[id] = AlbumFacts(genres, row.optInt("y").takeIf { it in 1900..2100 })
        }
        return out
    }

    /** What Ketmix measured of [track], from any of the stretches it read. */
    fun signals(track: Track): SoundSignals? {
        if (track.durationMs <= 0) return null
        val read = runCatching {
            AnalysisStore.body(track.id, track.durationMs) ?: AnalysisStore.intro(track.id, track.durationMs)
                ?: AnalysisStore.outro(track.id, track.durationMs)
        }.getOrNull() ?: return null
        val bpm = read.bpm.takeIf { it > 0f && read.confidence >= .3f }
        val loudness = read.loudnessDb.takeIf { it > -60f }
        val minor = read.key.takeIf { it in 0..23 && read.keyStrength >= MusicalKey.MIN_STRENGTH }?.let { it >= 12 }
        if (bpm == null && loudness == null && minor == null) return null
        return SoundSignals(bpm, loudness, minor)
    }

    /** Takes up what the other process measured since (the work process prepares downloads). */
    fun refreshSignals() {
        val app = context ?: return
        runCatching { AnalysisStore.init(app); AnalysisStore.refresh() }
    }

    /**
     * The facts of every title of [tracks], by id. Titles the catalogue said nothing of take the
     * style and language most titles of the same artist share.
     */
    fun build(tracks: List<Track>, sound: (Track) -> SoundSignals? = ::signals): Map<String, TrackFacts> {
        val first = tracks.associate { it.id to Facts.of(it, album(it.albumId), sound(it), charts[it.id]) }
        val artistGenres = Facts.artistGenres(first.values)
        val artistLangs = Facts.artistLanguages(first.values)
        return tracks.associate { track ->
            val facts = first.getValue(track.id)
            val filled = if (facts.genres.isNotEmpty() && facts.lang != null) facts else Facts.of(track, album(track.albumId), facts.sound,
                charts[track.id], artistGenres[facts.artist].orEmpty(), artistLangs[facts.artist])
            track.id to filled
        }
    }
}
