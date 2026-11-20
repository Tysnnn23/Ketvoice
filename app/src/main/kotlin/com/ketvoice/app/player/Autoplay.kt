package com.ketvoice.app.player

import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Recordings
import com.ketvoice.app.data.YouTubeJson
import com.ketvoice.app.smartlib.TrackFacts
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Lecture automatique » (7.0): when the listener's queue comes near its end, titles of the same
 * style follow, as Spotify does. They come from YouTube Music's radio of the exact title (the « À
 * suivre » panel of the watch playlist `RDAMVM<id>`, as `/catalog/radio` reads it), then from the
 * catalogue's radio of the artist, and without network from the downloads of the same artist,
 * style or feel. These are the pure rules; [Player] fetches and queues.
 */
internal object AutoplayRules {
    /** Titles added at once. */
    const val BATCH = 10
    /** The next batch is fetched when this many titles or fewer are left after the current one. */
    const val AHEAD = 3
    /** An artist appears at most this many times in one batch. */
    const val PER_ARTIST = 2
    /** Tempos further apart than this ratio (after halving or doubling) do not follow each other. */
    const val TEMPO_RATIO = 1.22f
    /** Loudness further apart than this (dB) means a very different energy. */
    const val ENERGY_DB = 9f

    /** What Ketmix measured of a title, when it did. */
    data class Feel(val bpm: Float?, val loudnessDb: Float?)

    fun artistKey(track: Track): String = MediaOverrides.normalize(MediaOverrides.mainArtist(track.artist))
    private fun recording(track: Track): String = Recordings.of(track)

    /** True when both titles were measured and their tempo or energy is very different. */
    fun tooFar(a: Feel?, b: Feel?): Boolean {
        if (a == null || b == null) return false
        val tempo = if (a.bpm != null && b.bpm != null && a.bpm > 0f && b.bpm > 0f) {
            var ratio = maxOf(a.bpm, b.bpm) / minOf(a.bpm, b.bpm)
            // Half or double time reads as the same pulse.
            while (ratio >= 1.9f) ratio /= 2f
            ratio > TEMPO_RATIO
        } else false
        val energy = a.loudnessDb != null && b.loudnessDb != null && kotlin.math.abs(a.loudnessDb - b.loudnessDb) > ENERGY_DB
        return tempo || energy
    }

    /**
     * The next titles from [candidates] (in the order the source ranks them): never the seed, a
     * title already in [queue] or [heard] in this session (by id, video or recording), a blocked
     * artist ([allows]), a title twice, an artist more than [PER_ARTIST] times, or a very different
     * tempo or energy than [seed] when both are known.
     */
    fun pick(candidates: List<Track>, seed: Track, queue: List<Track>, heard: Set<String>, allows: (Track) -> Boolean,
             feel: (Track) -> Feel?, batch: Int = BATCH): List<Track> {
        val seen = HashSet<String>()
        fun keys(t: Track) = listOfNotNull(t.id.takeIf { it.isNotBlank() }, t.videoId?.let { "v:$it" }, "r:" + recording(t))
        (queue + seed).forEach { seen += keys(it) }
        seen += heard
        val seedFeel = feel(seed)
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<Track>()
        for (t in candidates) {
            if (out.size >= batch) break
            if (t.id.isBlank() || t.title.isBlank()) continue
            val k = keys(t)
            if (k.any { it in seen }) continue
            if (!allows(t)) continue
            val artist = artistKey(t)
            if ((perArtist[artist] ?: 0) >= PER_ARTIST) continue
            if (tooFar(seedFeel, feel(t))) continue
            seen += k
            perArtist[artist] = (perArtist[artist] ?: 0) + 1
            out += t
        }
        return out
    }

    /** What [pick] and the session remember of a title so it is never queued twice. */
    fun heardKeys(t: Track): List<String> = listOfNotNull(t.id.takeIf { it.isNotBlank() }, t.videoId?.let { "v:$it" }, "r:" + recording(t))

    /**
     * Without network: downloaded titles closest to [seed], first by the same artist, then sharing
     * a style, then close in feel (tempo, minor or major), each group in a [random] order so two
     * sessions differ; nothing that [pick] would refuse.
     */
    fun offline(seed: Track, downloads: List<Track>, facts: Map<String, TrackFacts>, queue: List<Track>, heard: Set<String>,
                allows: (Track) -> Boolean, random: kotlin.random.Random, batch: Int = BATCH): List<Track> {
        val seedFacts = facts[seed.id]
        val seedArtist = artistKey(seed)
        fun score(t: Track): Int {
            val f = facts[t.id]
            var s = 0
            if (artistKey(t) == seedArtist) s += 4
            if (seedFacts != null && f != null && seedFacts.genres.isNotEmpty() && f.genres.any { it in seedFacts.genres }) s += 3
            val a = seedFacts?.sound; val b = f?.sound
            if (a != null && b != null) {
                if (!tooFar(Feel(a.bpm, a.loudnessDb), Feel(b.bpm, b.loudnessDb))) s += 1
                if (a.minor != null && a.minor == b.minor) s += 1
            }
            return s
        }
        val ranked = downloads.shuffled(random).map { it to score(it) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }
        // Feel is already part of the score: nothing measured is dropped offline.
        return pick(ranked, seed, queue, heard, allows, { null }, batch)
    }

    /**
     * The tracks of an InnerTube `next` answer for the watch playlist `RDAMVM<seed>`, in its order,
     * the seed itself left out (it is already playing). Podcasts and rows without an artist are skipped.
     */
    fun parseRadio(raw: JSONObject, seed: String): List<Track> {
        val out = ArrayList<Track>()
        val ids = HashSet<String>()
        for (item in YouTubeJson.objects(raw, "playlistPanelVideoRenderer")) {
            val watch = item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
            val video = item.optString("videoId").ifBlank { watch?.optString("videoId").orEmpty() }
            if (!VIDEO.matches(video) || video == seed || !ids.add(video)) continue
            val type = watch?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType").orEmpty()
            if (type == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") continue
            val name = YouTubeJson.text(item.optJSONObject("title")).trim()
            if (name.isBlank()) continue
            val artists = ArrayList<String>()
            var artistId: String? = null
            var album = ""
            var albumId: String? = null
            val runs = item.optJSONObject("longBylineText")?.optJSONArray("runs") ?: JSONArray()
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val text = run.optString("text").trim()
                val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val page = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
                if (text.isBlank()) continue
                if (page == "MUSIC_PAGE_TYPE_ARTIST" || page == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                    artists += text
                    if (artistId == null && page == "MUSIC_PAGE_TYPE_ARTIST") artistId = browse?.optString("browseId")?.takeIf { it.isNotBlank() }
                } else if (page == "MUSIC_PAGE_TYPE_ALBUM") {
                    album = text
                    albumId = browse?.optString("browseId")?.takeIf { it.isNotBlank() }
                }
            }
            val artist = artists.distinct().joinToString(", ").ifBlank { YouTubeJson.text(item.optJSONObject("shortBylineText")).trim() }
            if (artist.isBlank()) continue
            val title = if (type == "MUSIC_VIDEO_TYPE_ATV") name else cleanVideoTitle(name, artist)
            out += Track("yt:$video", title, artist, album, durationMs = duration(YouTubeJson.text(item.optJSONObject("lengthText"))),
                coverUrl = YouTubeJson.image(item.optJSONObject("thumbnail")), videoId = video, youtubeCatalogVerified = true,
                albumId = albumId, artistId = artistId)
            if (out.size >= 50) break
        }
        return out
    }

    private val VIDEO = Regex("[A-Za-z0-9_-]{11}")

    /** « Artist - Title (Official Video) » becomes « Title ». */
    internal fun cleanVideoTitle(name: String, artist: String): String {
        var t = name
        val main = MediaOverrides.mainArtist(artist)
        if (main.isNotBlank() && t.startsWith("$main - ", ignoreCase = true)) t = t.substring(main.length + 3)
        t = t.replace(Regex("\\s*[(\\[](official|clip|vid[ée]o|audio|lyrics?|paroles|visualizer)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        return t.trim().ifBlank { name }
    }

    private fun duration(text: String): Long {
        val parts = text.trim().split(":").map { it.toLongOrNull() ?: return 0L }
        if (parts.isEmpty() || parts.size > 3) return 0L
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }
}
